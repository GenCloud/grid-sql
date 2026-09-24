/*
 * Copyright 2024-2026 GenCloud
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.genfork.grid.catalog;

import com.google.common.annotations.VisibleForTesting;
import org.genfork.grid.fs.GridFs;
import org.genfork.grid.utils.AtomicBitSet;
import org.genfork.grid.utils.HexBytes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free hi-water sequence allocator with optional RECLAIM bitset (opt-in reuse).
 * <p>
 * Default path is monotonic no-reuse (PK safety): CAS on {@link AtomicLong} plus a
 * reserved durable window flushed every {@link #PERSIST_BATCH_ALLOCS} values (gaps on crash
 * are OK; duplicates are not). RECLAIM mode uses {@link AtomicBitSet} CAS — no monitors.
 * <p>
 * Durable I/O goes through {@link GridFs}. No Reactor {@code .block()}; never holds a
 * monitor across SQL / Netty EL wait.
 *
 * @author: GenCloud
 * @date: 2025/07
 * @since: 1.0
 */
public final class SequenceAllocator {
	private static final String META_SUFFIX = ".seq";
	private static final String LINE_HI = "hi=";
	private static final String LINE_RECLAIM = "reclaim=";
	/** Values reserved / flushed per durable window (monotonic path). */
	private static final int PERSIST_BATCH_ALLOCS = 64;
	/** Reclaim / release mutations before forcing a meta flush. */
	private static final int RECLAIM_DIRTY_FLUSH_EVERY = 32;

	private final SequenceDef def;
	/** Next value to hand out. */
	private final AtomicLong nextValue;
	/**
	 * Exclusive end of the pre-persisted reservation window ({@code hi=} on disk).
	 * Issuing values inside the open window needs no I/O.
	 */
	private final AtomicLong durableLimit;
	private final AtomicBitSet reclaimBits;
	private final AtomicLong reclaimDirtyCount;
	private final AtomicBoolean reclaimDirty;
	private final AtomicBoolean persisting = new AtomicBoolean(false);
	private final AtomicLong pendingPersistHi = new AtomicLong(Long.MIN_VALUE);
	private final Path metaFile;

	public SequenceAllocator(SequenceDef def, Path catalogDir) {
		this.def = Objects.requireNonNull(def, "def");
		this.nextValue = new AtomicLong(def.startValue());
		this.durableLimit = new AtomicLong(def.startValue());
		this.reclaimBits = def.reclaim() ? new AtomicBitSet() : null;
		this.reclaimDirtyCount = def.reclaim() ? new AtomicLong(0L) : null;
		this.reclaimDirty = def.reclaim() ? new AtomicBoolean(false) : null;
		this.metaFile = catalogDir == null
				? null
				: catalogDir.resolve(def.name().toLowerCase(Locale.ROOT) + META_SUFFIX);
		load();
	}

	public SequenceDef def() {
		return def;
	}

	/** Next value (advances hi-water or reclaims a hole when enabled). */
	public long nextVal() {
		if (reclaimBits != null) {
			final long reused = tryReclaim();
			if (reused >= 0L) {
				markReclaimDirtyAndMaybeFlush();
				return reused;
			}
		}
		return nextMonotonic();
	}

	/** Current next-to-allocate peek (does not advance). Prefer session {@code currval}. */
	public long peekHiWater() {
		return nextValue.get();
	}

	/**
	 * Return a value to the reclaim pool (only when {@link SequenceDef#reclaim()}).
	 * No-op for monotonic sequences.
	 */
	public void release(long value) {
		if (reclaimBits == null) {
			return;
		}
		if (value < def.startValue() && def.increment() > 0L) {
			return;
		}
		final int bit = toBit(value);
		if (bit >= 0) {
			reclaimBits.set(bit);
			markReclaimDirtyAndMaybeFlush();
		}
	}

	/**
	 * Persist reclaim mask when dirty. Safe on catalog / TX commit boundaries.
	 */
	public void flushIfDirty() {
		if (metaFile == null || reclaimDirty == null) {
			return;
		}
		if (!reclaimDirty.compareAndSet(true, false)) {
			return;
		}
		persistMeta(durableLimit.get());
		if (reclaimDirtyCount != null) {
			reclaimDirtyCount.set(0L);
		}
	}

	/**
	 * Force-persist current durable limit + reclaim snapshot (tests / shutdown).
	 */
	public void flush() {
		if (metaFile == null) {
			return;
		}
		ensureDurableWindow();
		persistMeta(durableLimit.get());
		if (reclaimDirty != null) {
			reclaimDirty.set(false);
		}
		if (reclaimDirtyCount != null) {
			reclaimDirtyCount.set(0L);
		}
	}

	private long nextMonotonic() {
		for (;;) {
			ensureDurableWindow();
			final long n = nextValue.get();
			final long limit = durableLimit.get();
			if (!inWindow(n, limit)) {
				continue;
			}
			final long advanced = n + def.increment();
			if (nextValue.compareAndSet(n, advanced)) {
				return n;
			}
		}
	}

	/**
	 * Reserve the next batch on disk before handing values out (PK-safe gaps on crash).
	 */
	private void ensureDurableWindow() {
		for (;;) {
			final long limit = durableLimit.get();
			final long n = nextValue.get();
			if (inWindow(n, limit)) {
				return;
			}
			final long newLimit = limit + ((long) PERSIST_BATCH_ALLOCS) * def.increment();
			if (durableLimit.compareAndSet(limit, newLimit)) {
				if (metaFile != null) {
					persistMeta(newLimit);
				}
				return;
			}
		}
	}

	private boolean inWindow(long next, long limit) {
		if (def.increment() > 0L) {
			return next < limit;
		}
		return next > limit;
	}

	private long tryReclaim() {
		final int bit = reclaimBits.claimNextSetBit();
		if (bit < 0) {
			return -1L;
		}
		return fromBit(bit);
	}

	private void markReclaimDirtyAndMaybeFlush() {
		if (reclaimDirty == null || reclaimDirtyCount == null) {
			return;
		}
		reclaimDirty.set(true);
		final long n = reclaimDirtyCount.incrementAndGet();
		if (n >= RECLAIM_DIRTY_FLUSH_EVERY) {
			flushIfDirty();
		}
	}

	private int toBit(long value) {
		final long delta = value - def.startValue();
		if (delta < 0L || delta > Integer.MAX_VALUE) {
			return -1;
		}
		if (def.increment() != 1L) {
			if (delta % def.increment() != 0L) {
				return -1;
			}
			final long idx = delta / def.increment();
			if (idx < 0L || idx > Integer.MAX_VALUE) {
				return -1;
			}
			return (int) idx;
		}
		return (int) delta;
	}

	private long fromBit(int bit) {
		return def.startValue() + ((long) bit) * def.increment();
	}

	private void load() {
		if (metaFile == null || !GridFs.isRegularFile(metaFile)) {
			return;
		}
		try {
			for (String line : GridFs.readLines(metaFile)) {
				if (line.startsWith(LINE_HI)) {
					final long hi = Long.parseLong(line.substring(LINE_HI.length()).trim());
					nextValue.set(hi);
					durableLimit.set(hi);
				} else if (line.startsWith(LINE_RECLAIM) && reclaimBits != null) {
					final String hex = line.substring(LINE_RECLAIM.length()).trim();
					if (!hex.isEmpty()) {
						reclaimBits.clearAll();
						reclaimBits.orPacked(HexBytes.fromHex(hex));
					}
				}
			}
		} catch (IOException | NumberFormatException e) {
			throw new IllegalStateException("Failed to load sequence meta " + metaFile, e);
		}
	}

	private void persistMeta(long hiExclusive) {
		if (metaFile == null) {
			return;
		}
		pendingPersistHi.set(hiExclusive);
		drainPersist();
	}

	private void drainPersist() {
		if (!persisting.compareAndSet(false, true)) {
			return;
		}
		long written = Long.MIN_VALUE;
		try {
			for (;;) {
				written = pendingPersistHi.get();
				writeMetaFile(written);
				if (pendingPersistHi.get() == written) {
					break;
				}
			}
		} finally {
			persisting.set(false);
		}
		if (pendingPersistHi.get() != written) {
			drainPersist();
		}
	}

	private void writeMetaFile(long hiExclusive) {
		try {
			final StringBuilder sb = new StringBuilder();
			sb.append(LINE_HI).append(hiExclusive).append('\n');
			if (reclaimBits != null) {
				sb.append(LINE_RECLAIM).append(HexBytes.toHex(reclaimBits.toByteArray())).append('\n');
			}
			GridFs.writeAtomic(metaFile, sb.toString());
		} catch (IOException e) {
			throw new IllegalStateException("Failed to persist sequence " + def.name(), e);
		}
	}

	public void deleteMeta() {
		if (metaFile == null) {
			return;
		}
		GridFs.deleteQuietly(metaFile);
	}

	@VisibleForTesting
	public int persistBatchAllocs() {
		return PERSIST_BATCH_ALLOCS;
	}

	@VisibleForTesting
	public long durableLimitForTest() {
		return durableLimit.get();
	}
}