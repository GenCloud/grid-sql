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
package org.genfork.grid.sql.tx;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import com.google.common.annotations.VisibleForTesting;

import org.genfork.grid.metrics.SqlLockMetrics;
import org.genfork.grid.threading.ThreadService;

/**
 * Tracks peer-held FOR UPDATE row locks by {@code txId} for prepare votes and abort release.
 * <p>
 * Used only on logic VT (Netty inbound enqueue), never on the event loop.
 * Lease TTL expires orphans after writer crash (scheduled tick → logic VT release).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class ForUpdatePeerHeldKeys {

	/** Peer lock lease TTL after writer remember (orphan cleanup on crash). */
	private static final long PEER_LOCK_LEASE_TTL_MS = 30_000L;
	private static final long DEFAULT_SWEEP_INTERVAL_MS = 1_000L;
	private static final long MIN_LEASE_TTL_MS = 1L;

	private final ConcurrentHashMap<Long, ConcurrentHashMap<String, ConcurrentHashMap<KeyWrapper, Long>>> byTx =
			new ConcurrentHashMap<>();
	private final AtomicLong leaseTtlMs = new AtomicLong(PEER_LOCK_LEASE_TTL_MS);
	private volatile LongSupplier nanoClock = System::nanoTime;
	private volatile SqlRecordLockManager locks;
	private final AtomicReference<ScheduledFuture<?>> sweepFuture = new AtomicReference<>();

	public void bindLockManager(SqlRecordLockManager locks) {
		this.locks = locks;
	}

	/**
	 * Start TTL sweeper once (idempotent). Called lazily from {@link #remember} so
	 * Dist-unused nodes do not pay a periodic tick.
	 */
	public void startLeaseSweeper() {
		final ScheduledFuture<?> existing = sweepFuture.get();
		if (existing != null && !existing.isCancelled()) {
			return;
		}
		final ScheduledFuture<?> scheduled = ThreadService.getScheduledExecutor().scheduleAtFixedRate(
				() -> ThreadService.getLogicExecutor().execute(this::sweepExpired),
				DEFAULT_SWEEP_INTERVAL_MS,
				DEFAULT_SWEEP_INTERVAL_MS,
				TimeUnit.MILLISECONDS);
		if (!sweepFuture.compareAndSet(existing, scheduled)) {
			scheduled.cancel(false);
		}
	}

	public void stopLeaseSweeper() {
		final ScheduledFuture<?> f = sweepFuture.getAndSet(null);
		if (f != null) {
			f.cancel(false);
		}
	}

	@VisibleForTesting
	public void setLeaseTtlMs(long ttlMs) {
		leaseTtlMs.set(Math.max(MIN_LEASE_TTL_MS, ttlMs));
	}

	@VisibleForTesting
	public void setNanoClock(LongSupplier nanoClock) {
		this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
	}

	@VisibleForTesting
	public void sweepExpiredForTest() {
		sweepExpired();
	}

	public void remember(long txId, String table, byte[] key) {
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
		startLeaseSweeper();
		final long expireAt = nanoClock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(leaseTtlMs.get());
		byTx.computeIfAbsent(txId, ignored -> new ConcurrentHashMap<>())
				.computeIfAbsent(table, ignored -> new ConcurrentHashMap<>())
				.put(new KeyWrapper(key), Long.valueOf(expireAt));
	}

	public void forget(long txId, String table, byte[] key) {
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
		final ConcurrentHashMap<String, ConcurrentHashMap<KeyWrapper, Long>> tables = byTx.get(txId);
		if (tables == null) {
			return;
		}
		final ConcurrentHashMap<KeyWrapper, Long> keys = tables.get(table);
		if (keys == null) {
			return;
		}
		keys.remove(new KeyWrapper(key));
		if (keys.isEmpty()) {
			tables.remove(table, keys);
		}
		if (tables.isEmpty()) {
			byTx.remove(txId, tables);
		}
	}

	public boolean hasAny(long txId) {
		final ConcurrentHashMap<String, ConcurrentHashMap<KeyWrapper, Long>> tables = byTx.get(txId);
		if (tables == null || tables.isEmpty()) {
			return false;
		}
		for (ConcurrentHashMap<KeyWrapper, Long> keys : tables.values()) {
			if (keys != null && !keys.isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Prepare vote: every requested key must still be held for {@code txId}.
	 * Empty/null key-set is not prepared (fail-closed — no “any held” shortcut).
	 */
	public boolean containsAll(long txId, List<ForUpdatePrepareWireUtil.TableKey> keys) {
		if (keys == null || keys.isEmpty()) {
			return false;
		}
		final ConcurrentHashMap<String, ConcurrentHashMap<KeyWrapper, Long>> tables = byTx.get(txId);
		if (tables == null || tables.isEmpty()) {
			return false;
		}
		for (ForUpdatePrepareWireUtil.TableKey tk : keys) {
			final ConcurrentHashMap<KeyWrapper, Long> held = tables.get(tk.table());
			if (held == null || !held.containsKey(new KeyWrapper(tk.key()))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Unlock all remembered keys for {@code txId} and drop the tracking entry (idempotent).
	 */
	public void releaseAll(long txId, SqlRecordLockManager lockManager) {
		final ConcurrentHashMap<String, ConcurrentHashMap<KeyWrapper, Long>> tables = byTx.remove(txId);
		if (tables == null || lockManager == null) {
			return;
		}
		for (Map.Entry<String, ConcurrentHashMap<KeyWrapper, Long>> te : tables.entrySet()) {
			final String table = te.getKey();
			final ConcurrentHashMap<KeyWrapper, Long> keys = te.getValue();
			if (keys == null) {
				continue;
			}
			for (KeyWrapper kw : keys.keySet()) {
				try {
					lockManager.unlock(table, kw.key());
				} catch (RuntimeException ignored) {
					// idempotent
				}
			}
		}
	}

	public void clear() {
		byTx.clear();
	}

	private void sweepExpired() {
		final long now = nanoClock.getAsLong();
		final SqlRecordLockManager lockManager = locks;
		final ArrayList<Long> expiredTx = new ArrayList<>();
		for (Map.Entry<Long, ConcurrentHashMap<String, ConcurrentHashMap<KeyWrapper, Long>>> txEntry : byTx.entrySet()) {
			final ConcurrentHashMap<String, ConcurrentHashMap<KeyWrapper, Long>> tables = txEntry.getValue();
			boolean anyLeft = false;
			for (Map.Entry<String, ConcurrentHashMap<KeyWrapper, Long>> te : tables.entrySet()) {
				final String table = te.getKey();
				final ConcurrentHashMap<KeyWrapper, Long> keys = te.getValue();
				for (Map.Entry<KeyWrapper, Long> ke : keys.entrySet()) {
					final Long expireAt = ke.getValue();
					if (expireAt != null && expireAt.longValue() <= now) {
						keys.remove(ke.getKey(), expireAt);
						if (lockManager != null) {
							try {
								lockManager.unlock(table, ke.getKey().key());
							} catch (RuntimeException ignored) {
							}
						}
						SqlLockMetrics.recordPeerLeaseExpired();
					} else {
						anyLeft = true;
					}
				}
				if (keys.isEmpty()) {
					tables.remove(table, keys);
				}
			}
			if (!anyLeft && tables.isEmpty()) {
				expiredTx.add(txEntry.getKey());
			}
		}
		for (Long txId : expiredTx) {
			byTx.remove(txId);
		}
	}
}