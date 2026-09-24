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
package org.genfork.grid.replication.repair;

import com.google.common.annotations.VisibleForTesting;
import org.genfork.grid.codec.duplex.DuplexBlob;
import org.genfork.grid.codec.duplex.DuplexCodecSupport;
import org.genfork.grid.codec.duplex.DuplexVerifier;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.genfork.grid.replication.tx.OpLogTxUnits;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.CRC32;

/**
 * Version-map homologous repair (DNA-repair inspired; not HDCRM patent clone).
 * Supports seq-range coalesce plus full-row {@link RepairCommandType#FETCH_ROW} checksum heal.
 * <p>
 * TX-aware: mid-unit {@code UPSERT}/{@code DELETE} between {@code TX_BEGIN} and
 * {@code TX_COMMIT}/{@code TX_ABORT} are buffered and do not update the locus map until commit;
 * abort discards the buffer. {@link #reconcile} is fail-closed while a stream has an open TX.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class HomologousRepair {
	/** Coalesce locus disk rewrites per {@code domain#shard} (named debounce). */
	private static final long LOCUS_PERSIST_DEBOUNCE_MS = 5_000L;
	/** Backoff when OpLog fsync p50 is elevated or locus map is large (O(keys) rewrite). */
	private static final long LOCUS_PERSIST_PRESSURE_DEBOUNCE_MS = 30_000L;
	/** Engage backoff below OpLog group-force p50 — host NVMe idle ≈1 ms. */
	private static final long LOCUS_FSYNC_PRESSURE_P50_NS = 1_500_000L;
	private static final long LOCUS_FSYNC_MIN_SAMPLES = 16L;
	/** Skip full-file rewrite cadence when shard locus exceeds this entry count. */
	private static final int LOCUS_LARGE_MAP_ENTRIES = 8_192;
	private static final long LOCUS_FLUSH_TIMEOUT_SEC = 30L;

	private final VersionLocusMap locusMap = new VersionLocusMap();
	private final LongAdder repairIssued = new LongAdder();
	private final LongAdder repairApplied = new LongAdder();
	private final FileDurableLocusStore durableStore;
	/** Optional last-known good row bytes by domain#shard#keyHash for FETCH_ROW heal. */
	private final Map<String, byte[]> rowBytesByLocus = new ConcurrentHashMap<>();
	private final Set<String> pendingPersist = ConcurrentHashMap.newKeySet();
	/** Keys with a debounce timer already scheduled. */
	private final Set<String> debounceScheduled = ConcurrentHashMap.newKeySet();
	/** Open TX refcount per {@code domain#shard} (concurrent units on same stream). */
	private final ConcurrentHashMap<String, Integer> openTxCounts = new ConcurrentHashMap<>();
	/** Pending loci + row bytes held until last TX_COMMIT for the stream. */
	private final Map<String, List<PendingLocus>> pendingTxLoci = new ConcurrentHashMap<>();
	/** Serializes {@link #observe} per stream (concurrent ArrayList.add races under multi-TX). */
	private final ConcurrentHashMap<String, Object> observeLocks = new ConcurrentHashMap<>();
	private static final int OPEN_TX_ONE = 1;

	private final ScheduledExecutorService locusPersistScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		final Thread t = new Thread(r, "locus-persist");
		t.setDaemon(true);
		return t;
	});

	private record PendingLocus(VersionLocus locus, byte[] rowBytesOrNull) {
	}

	public HomologousRepair() {
		this(null);
	}

	public HomologousRepair(Path locusDir) {
		this.durableStore = locusDir == null ? null : new FileDurableLocusStore(locusDir);
		if (durableStore != null) {
			durableStore.loadInto(locusMap);
		}
	}

	public VersionLocusMap getLocusMap() {
		return locusMap;
	}

	public void observe(ReplicationOp op) {
		if (op == null) {
			return;
		}
		final String stream = streamKey(op.domainType(), op.shard());
		final ReplicationOpType type = op.type();
		// TX markers mutate openTxCounts + pending lists — short monitor OK (no VT pin across I/O).
		if (type == ReplicationOpType.TX_BEGIN
				|| type == ReplicationOpType.TX_COMMIT
				|| type == ReplicationOpType.TX_ABORT
				|| openTxCounts.containsKey(stream)) {
			final Object lock = observeLocks.computeIfAbsent(stream, ignored -> new Object());
			synchronized (lock) {
				observeLocked(stream, op);
			}
			return;
		}
		// Autocommit / no open TX: ConcurrentHashMap locus path — no monitor on commit latency.
		observeLocked(stream, op);
	}

	/**
	 * Optional off-path observe (tests). Prefer {@link #observe} on the commit path —
	 * unbounded async enqueue on the single locus thread causes GC cliffs under WRITE_ONLY.
	 */
	public void observeAsync(ReplicationOp op) {
		observe(op);
	}

	private void observeLocked(String stream, ReplicationOp op) {
		final ReplicationOpType type = op.type();
		if (type == ReplicationOpType.TX_BEGIN) {
			openTxCounts.merge(stream, OPEN_TX_ONE, Integer::sum);
			pendingTxLoci.computeIfAbsent(stream, ignored -> new ArrayList<>());
			return;
		}
		if (type == ReplicationOpType.TX_COMMIT) {
			final boolean last = decrementOpenTx(stream);
			if (last) {
				final List<PendingLocus> pending = pendingTxLoci.remove(stream);
				if (pending != null) {
					for (PendingLocus p : pending) {
						installLocusOwned(p.locus(), p.rowBytesOrNull());
					}
				}
			}
			return;
		}
		if (type == ReplicationOpType.TX_ABORT) {
			final boolean last = decrementOpenTx(stream);
			if (last) {
				pendingTxLoci.remove(stream);
			}
			return;
		}
		if (OpLogTxUnits.isTxMarker(type)
				|| type == ReplicationOpType.BARRIER
				|| type == ReplicationOpType.SNAPSHOT_MARKER
				|| type == ReplicationOpType.DDL) {
			return;
		}
		if (op.key() == null) {
			return;
		}
		final long keyHash = keyHash(op.key());
		final long checksum = op.value() == null ? 0L : valueChecksum(op.value());
		final VersionLocus locus = new VersionLocus(
				op.domainType(), op.shard(), keyHash, op.opSeq(), op.schemaEpoch(), checksum
		);
		// Skip per-upsert row-byte cache: OpLog is SoT for FETCH_ROW / RESHIP; avoids O(value) copy + RAM.
		final byte[] rowCopy = null;
		if (openTxCounts.containsKey(stream)) {
			pendingTxLoci.computeIfAbsent(stream, ignored -> new ArrayList<>())
					.add(new PendingLocus(locus, rowCopy));
			return;
		}
		installLocusOwned(locus, rowCopy);
	}

	/** @return {@code true} when this stream has no remaining open TX units */
	private boolean decrementOpenTx(String stream) {
		final Integer left = openTxCounts.compute(stream, (ignored, cur) -> {
			if (cur == null || cur <= OPEN_TX_ONE) {
				return null;
			}
			return cur.intValue() - OPEN_TX_ONE;
		});
		return left == null;
	}

	/** True while this stream has seen {@code TX_BEGIN} without matching COMMIT/ABORT. */
	public boolean hasOpenTx(String domainType, int shard) {
		return openTxCounts.containsKey(streamKey(domainType, shard));
	}

	/** Caller transfers ownership of {@code rowBytesOrNull} (already a defensive copy). */
	private void installLocusOwned(VersionLocus locus, byte[] rowBytesOrNull) {
		locusMap.put(locus);
		if (rowBytesOrNull != null) {
			rowBytesByLocus.put(
					rowKey(locus.domainType(), locus.shard(), locus.keyHash()),
					rowBytesOrNull);
		}
		// Mark dirty only — durable rewrite is coalesced / deferred (see schedulePersist).
		schedulePersist(locus.domainType(), locus.shard());
	}

	private static String streamKey(String domainType, int shard) {
		return domainType + "#" + shard;
	}

	private void schedulePersist(String domainType, int shard) {
		if (durableStore == null) {
			return;
		}
		final String key = domainType + "#" + shard;
		pendingPersist.add(key);
		if (!debounceScheduled.add(key)) {
			return;
		}
		// Prefer long coalesce; under OpLog pressure or large locus use pressure debounce.
		final boolean fsyncPressure = ReplicationMetrics.oplogFsyncSampleCount() >= LOCUS_FSYNC_MIN_SAMPLES
				&& ReplicationMetrics.oplogFsyncP50Ns() >= LOCUS_FSYNC_PRESSURE_P50_NS;
		final boolean largeLocus = locusMap.size(domainType, shard) >= LOCUS_LARGE_MAP_ENTRIES;
		final long delayMs = (fsyncPressure || largeLocus)
				? LOCUS_PERSIST_PRESSURE_DEBOUNCE_MS
				: LOCUS_PERSIST_DEBOUNCE_MS;
		locusPersistScheduler.schedule(
				() -> flushPersistKey(key, domainType, shard),
				delayMs,
				TimeUnit.MILLISECONDS);
	}

	private void flushPersistKey(String key, String domainType, int shard) {
		debounceScheduled.remove(key);
		if (!pendingPersist.contains(key)) {
			return;
		}
		// Growing locus files are O(keys) rewrites — defer under OpLog fsync pressure / write load.
		final boolean fsyncPressure = ReplicationMetrics.oplogFsyncSampleCount() >= LOCUS_FSYNC_MIN_SAMPLES
				&& ReplicationMetrics.oplogFsyncP50Ns() >= LOCUS_FSYNC_PRESSURE_P50_NS;
		if (fsyncPressure) {
			if (debounceScheduled.add(key)) {
				locusPersistScheduler.schedule(
						() -> flushPersistKey(key, domainType, shard),
						LOCUS_PERSIST_PRESSURE_DEBOUNCE_MS,
						TimeUnit.MILLISECONDS);
			}
			return;
		}
		if (!pendingPersist.remove(key)) {
			return;
		}
		durableStore.persist(locusMap, domainType, shard);
		// Observes during persist re-queued under the same key — schedule another debounce.
		if (pendingPersist.contains(key) && debounceScheduled.add(key)) {
			locusPersistScheduler.schedule(
					() -> flushPersistKey(key, domainType, shard),
					LOCUS_PERSIST_DEBOUNCE_MS,
					TimeUnit.MILLISECONDS);
		}
	}

	/** Block until queued locus rewrites finish (tests / shutdown). */
	public void flushLocusPersists() {
		if (durableStore == null) {
			return;
		}
		final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
		locusPersistScheduler.execute(() -> {
			try {
				for (String key : Set.copyOf(pendingPersist)) {
					final int sep = key.lastIndexOf('#');
					if (sep <= 0 || sep >= key.length() - 1) {
						pendingPersist.remove(key);
						debounceScheduled.remove(key);
						continue;
					}
					final String domainType = key.substring(0, sep);
					final int shard = Integer.parseInt(key.substring(sep + 1));
					debounceScheduled.remove(key);
					if (pendingPersist.remove(key)) {
						durableStore.persist(locusMap, domainType, shard);
					}
				}
			} finally {
				done.countDown();
			}
		});
		try {
			done.await(LOCUS_FLUSH_TIMEOUT_SEC, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public List<RepairCommand> reconcile(String domainType, int shard, Map<Long, VersionLocus> remoteView) {
		// Fail-closed: do not emit repair while local stream still has an open TX unit.
		if (hasOpenTx(domainType, shard)) {
			return List.of();
		}
		final List<RepairCommand> commands = new ArrayList<>();
		final Map<Long, VersionLocus> local = locusMap.snapshot(domainType, shard);
		long minFetchFrom = Long.MAX_VALUE;
		long maxFetchTo = 0L;
		boolean needRange = false;
		for (Map.Entry<Long, VersionLocus> e : remoteView.entrySet()) {
			final VersionLocus remote = e.getValue();
			final VersionLocus ours = local.get(e.getKey());
			if (ours == null) {
				commands.add(new RepairCommand(RepairCommandType.FETCH_OP, domainType, shard,
						remote.keyHash(), 0L, remote.opSeq(), remote.contentChecksum()));
				repairIssued.increment();
				needRange = true;
				minFetchFrom = Math.min(minFetchFrom, Math.max(1L, remote.opSeq()));
				maxFetchTo = Math.max(maxFetchTo, remote.opSeq());
			} else if (ours.opSeq() == remote.opSeq() && ours.contentChecksum() != remote.contentChecksum()) {
				// Same seq, corrupted/mismatched value → full-row fetch.
				commands.add(new RepairCommand(RepairCommandType.FETCH_ROW, domainType, shard,
						remote.keyHash(), ours.opSeq(), remote.opSeq(), remote.contentChecksum()));
				repairIssued.increment();
			} else if (ours.opSeq() < remote.opSeq()
					|| (ours.opSeq() == remote.opSeq() && ours.contentChecksum() != remote.contentChecksum())) {
				commands.add(new RepairCommand(RepairCommandType.RESHIP_SEGMENT, domainType, shard,
						remote.keyHash(), ours.opSeq() + 1, remote.opSeq(), remote.contentChecksum()));
				repairIssued.increment();
				needRange = true;
				minFetchFrom = Math.min(minFetchFrom, ours.opSeq() + 1);
				maxFetchTo = Math.max(maxFetchTo, remote.opSeq());
			}
		}
		for (Map.Entry<Long, VersionLocus> e : local.entrySet()) {
			if (!remoteView.containsKey(e.getKey())) {
				commands.add(new RepairCommand(RepairCommandType.RESHIP_SEGMENT, domainType, shard,
						e.getKey(), e.getValue().opSeq(), e.getValue().opSeq(), e.getValue().contentChecksum()));
				repairIssued.increment();
			}
		}
		// Coalesce multi-key lag into one precise seq-range reship when beneficial.
		// Keep FETCH_ROW commands (full-row) outside the coalesce.
		if (needRange && maxFetchTo >= minFetchFrom && commands.stream()
				.filter(c -> c.type() != RepairCommandType.FETCH_ROW).count() > 2) {
			final List<RepairCommand> keptRows = commands.stream()
					.filter(c -> c.type() == RepairCommandType.FETCH_ROW)
					.toList();
			commands.clear();
			commands.addAll(keptRows);
			commands.add(new RepairCommand(RepairCommandType.RESHIP_SEGMENT, domainType, shard,
					0L, minFetchFrom, maxFetchTo, 0L));
			repairIssued.increment();
		}
		return commands;
	}

	public byte[] verifyOrRebuildValue(byte[] value) {
		if (value == null || !DuplexBlob.isWire(value)) {
			return value;
		}
		final DuplexVerifier verifier = DuplexCodecSupport.getCodec().getVerifier();
		try {
			final DuplexBlob repaired = verifier.verifyOrRepair(DuplexBlob.fromWireBytes(value));
			repairApplied.increment();
			ReplicationMetrics.recordDuplexRepair();
			return repaired.toWireBytes();
		} catch (RuntimeException ex) {
			ReplicationMetrics.recordDuplexUncorrectable();
			throw ex;
		}
	}

	/**
	 * Verify local row bytes against expected checksum; heal from {@code goodValue} when mismatched.
	 *
	 * @return healed (or original good) row bytes
	 */
	public byte[] healCorruptedRow(String domainType, int shard, long keyHash, long opSeq, long schemaEpoch,
	                               byte[] localValue, byte[] goodValue, long expectedChecksum) {
		final long localCs = localValue == null ? 0L : valueChecksum(localValue);
		if (localCs == expectedChecksum && expectedChecksum != 0L) {
			return localValue == null ? null : Arrays.copyOf(localValue, localValue.length);
		}
		byte[] healed = goodValue;
		if (healed == null) {
			throw new IllegalStateException("FETCH_ROW heal missing good bytes for keyHash=" + keyHash);
		}
		if (DuplexBlob.isWire(healed)) {
			healed = verifyOrRebuildValue(healed);
		}
		final long healedCs = valueChecksum(healed);
		if (expectedChecksum != 0L && healedCs != expectedChecksum) {
			throw new IllegalStateException("FETCH_ROW heal checksum mismatch for keyHash=" + keyHash);
		}
		applyHealedLocus(new VersionLocus(domainType, shard, keyHash, opSeq, schemaEpoch, healedCs));
		rowBytesByLocus.put(rowKey(domainType, shard, keyHash), Arrays.copyOf(healed, healed.length));
		return Arrays.copyOf(healed, healed.length);
	}

	/** Verify content checksum of value bytes (full-row path). */
	public boolean verifyValueChecksum(byte[] value, long expectedChecksum) {
		return valueChecksum(value) == expectedChecksum;
	}

	public long repairIssued() {
		return repairIssued.sum();
	}

	public long repairApplied() {
		return repairApplied.sum();
	}

	/**
	 * Apply a remote locus entry (or range heal) after RESHIP/FETCH: overwrite local checksum/seq.
	 */
	public void applyHealedLocus(VersionLocus healed) {
		if (healed == null) {
			return;
		}
		locusMap.put(healed);
		schedulePersist(healed.domainType(), healed.shard());
		repairApplied.increment();
	}

	/** Test/chaos helper: wipe or corrupt a keyHash entry so reconcile must heal. */
	@VisibleForTesting
	public void corruptLocus(String domainType, int shard, long keyHash) {
		locusMap.put(new VersionLocus(domainType, shard, keyHash, 0L, 0L, 0xDEADL));
		schedulePersist(domainType, shard);
		flushLocusPersists();
	}

	/** Test/chaos helper: corrupt cached row bytes while keeping locus seq (triggers FETCH_ROW). */
	@VisibleForTesting
	public void corruptRowBytes(String domainType, int shard, long keyHash) {
		final String rk = rowKey(domainType, shard, keyHash);
		final byte[] prev = rowBytesByLocus.get(rk);
		if (prev == null) {
			rowBytesByLocus.put(rk, new byte[]{(byte) 0xDE, (byte) 0xAD});
		} else {
			final byte[] bad = Arrays.copyOf(prev, prev.length);
			bad[0] ^= 0x5A;
			rowBytesByLocus.put(rk, bad);
		}
		final VersionLocus cur = locusMap.snapshot(domainType, shard).get(keyHash);
		if (cur != null) {
			locusMap.put(new VersionLocus(domainType, shard, keyHash, cur.opSeq(), cur.schemaEpoch(), 0xDEADL));
			schedulePersist(domainType, shard);
			flushLocusPersists();
		}
	}

	public byte[] cachedRowBytes(String domainType, int shard, long keyHash) {
		final byte[] v = rowBytesByLocus.get(rowKey(domainType, shard, keyHash));
		return v == null ? null : Arrays.copyOf(v, v.length);
	}

	public static long keyHash(byte[] key) {
		long h = 1125899906842597L;
		for (byte b : key) {
			h = 31 * h + (b & 0xFF);
		}
		return h;
	}

	public static long valueChecksum(byte[] value) {
		if (value == null) {
			return 0L;
		}
		final CRC32 crc = new CRC32();
		crc.update(value);
		return crc.getValue();
	}

	private static String rowKey(String domainType, int shard, long keyHash) {
		return domainType + "#" + shard + "#" + Long.toHexString(keyHash);
	}
}
