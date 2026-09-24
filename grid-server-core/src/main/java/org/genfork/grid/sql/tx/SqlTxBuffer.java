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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-session dirty buffer for an open SQL transaction.
 * Mutations stay invisible in {@code TableStore} until {@link SqlTxCommitter#commit}.
 * <p>
 * SAVEPOINT / ROLLBACK TO / RELEASE are in-memory only (no OpLog) until COMMIT.
 *
 * @author: GenCloud
 * @date: 2026/01
 * @since: 1.0
 */
public final class SqlTxBuffer {
	private static final long INITIAL_TX_ID = 1L;
	private static final AtomicLong TX_IDS = new AtomicLong(INITIAL_TX_ID);
	private static final String ERR_NO_SAVEPOINT = "savepoint does not exist: ";

	public enum Op {
		UPSERT,
		DELETE
	}

	public record DirtyEntry(Op op, byte[] keyBytes, byte[] valueBytesOrNull, int shard) {
	}

	public record Participation(String table, int shard) {
	}

	public record LockedKey(String table, KeyWrapper key) {
	}

	/**
	 * Locks released by {@link #rollbackTo(String)} (caller unlocks).
	 *
	 * @author: GenCloud
	 * @date: 2026/01
	 * @since: 1.0
	 */
	public record SavepointRollback(List<LockedKey> localLocks, List<DistForUpdatePeerLockLease> peerLocks) {
	}

	/**
	 * One overwritten dirty entry retained for savepoint rollback.
	 *
	 * @author: GenCloud
	 * @date: 2026/01
	 * @since: 1.0
	 */
	private record UndoPut(String table, KeyWrapper key, DirtyEntry priorOrNull) {
	}

	/**
	 * Undo-log and lock-list boundaries for one named savepoint.
	 *
	 * @author: GenCloud
	 * @date: 2026/01
	 * @since: 1.0
	 */
	private record SavepointFrame(
			String name,
			int lockedSize,
			int peerSize,
			int undoStart
	) {
	}

	private final long txId;
	private final Map<String, Map<KeyWrapper, DirtyEntry>> byTable = new LinkedHashMap<>();
	private final Set<Participation> participations = new LinkedHashSet<>();
	private final List<LockedKey> locked = new ArrayList<>();
	private final List<DistForUpdatePeerLockLease> peerLocks = new ArrayList<>();
	private final Deque<SavepointFrame> savepoints = new ArrayDeque<>();
	private final List<UndoPut> undoLog = new ArrayList<>();

	public SqlTxBuffer() {
		this(TX_IDS.getAndIncrement());
	}

	public SqlTxBuffer(long txId) {
		this.txId = txId;
	}

	public long txId() {
		return txId;
	}

	public void put(String table, DirtyEntry entry) {
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(entry, "entry");
		final KeyWrapper key = new KeyWrapper(entry.keyBytes());
		final Map<KeyWrapper, DirtyEntry> map = byTable.computeIfAbsent(table, t -> new LinkedHashMap<>());
		final DirtyEntry prior = map.get(key);
		final Participation part = new Participation(table, entry.shard());
		if (!savepoints.isEmpty()) {
			undoLog.add(new UndoPut(table, key, prior));
		}
		map.put(key, entry);
		participations.add(part);
	}

	public DirtyEntry get(String table, byte[] keyBytes) {
		final Map<KeyWrapper, DirtyEntry> m = byTable.get(table);
		if (m == null) {
			return null;
		}
		return m.get(new KeyWrapper(keyBytes));
	}

	public Map<KeyWrapper, DirtyEntry> entriesForTable(String table) {
		final Map<KeyWrapper, DirtyEntry> m = byTable.get(table);
		return m == null ? Map.of() : m;
	}

	public Set<Participation> participations() {
		return Set.copyOf(participations);
	}

	public Collection<Map.Entry<String, Map<KeyWrapper, DirtyEntry>>> tables() {
		return byTable.entrySet();
	}

	public boolean isEmpty() {
		return byTable.isEmpty();
	}

	public void rememberLock(String table, KeyWrapper key) {
		locked.add(new LockedKey(table, key));
	}

	public boolean holdsLock(String table, KeyWrapper key) {
		for (LockedKey lk : locked) {
			if (lk.table().equals(table) && lk.key().equals(key)) {
				return true;
			}
		}
		return false;
	}

	public void forgetLock(String table, KeyWrapper key) {
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
		for (int i = 0; i < locked.size(); i++) {
			final LockedKey lk = locked.get(i);
			if (lk.table().equals(table) && lk.key().equals(key)) {
				locked.remove(i);
				return;
			}
		}
	}

	public void rememberPeerLock(DistForUpdatePeerLockLease lease) {
		Objects.requireNonNull(lease, "lease");
		peerLocks.add(lease);
	}

	public List<DistForUpdatePeerLockLease> peerLockedLeases() {
		return List.copyOf(peerLocks);
	}

	public List<LockedKey> lockedKeys() {
		return List.copyOf(locked);
	}

	public void savepoint(String name) {
		final String key = normalizeName(name);
		savepoints.addLast(new SavepointFrame(key, locked.size(), peerLocks.size(), undoLog.size()));
	}

	/**
	 * Roll dirty/locks back to named savepoint. Returns locks acquired after the marker
	 * (caller must unlock via {@link SqlRecordLockManager}).
	 */
	public SavepointRollback rollbackTo(String name) {
		final String key = normalizeName(name);
		SavepointFrame target = null;
		while (!savepoints.isEmpty()) {
			final SavepointFrame top = savepoints.peekLast();
			if (top.name().equals(key)) {
				target = top;
				break;
			}
			savepoints.removeLast();
		}
		if (target == null) {
			throw new IllegalStateException(ERR_NO_SAVEPOINT + name);
		}
		while (undoLog.size() > target.undoStart()) {
			final UndoPut u = undoLog.remove(undoLog.size() - 1);
			applyUndo(u);
		}
		final List<LockedKey> releasedLocal = new ArrayList<>();
		while (locked.size() > target.lockedSize()) {
			releasedLocal.add(locked.remove(locked.size() - 1));
		}
		final List<DistForUpdatePeerLockLease> releasedPeer = new ArrayList<>();
		while (peerLocks.size() > target.peerSize()) {
			releasedPeer.add(peerLocks.remove(peerLocks.size() - 1));
		}
		rebuildParticipations();
		return new SavepointRollback(List.copyOf(releasedLocal), List.copyOf(releasedPeer));
	}

	public void releaseSavepoint(String name) {
		final String key = normalizeName(name);
		boolean found = false;
		for (SavepointFrame frame : savepoints) {
			if (frame.name().equals(key)) {
				found = true;
			}
		}
		if (!found) {
			throw new IllegalStateException(ERR_NO_SAVEPOINT + name);
		}
		while (!savepoints.isEmpty()) {
			final SavepointFrame top = savepoints.removeLast();
			if (top.name().equals(key)) {
				break;
			}
		}
		if (savepoints.isEmpty()) {
			undoLog.clear();
		}
	}

	public void clear() {
		byTable.clear();
		participations.clear();
		locked.clear();
		peerLocks.clear();
		savepoints.clear();
		undoLog.clear();
	}

	private void applyUndo(UndoPut u) {
		final Map<KeyWrapper, DirtyEntry> map = byTable.get(u.table());
		if (map == null) {
			return;
		}
		if (u.priorOrNull() == null) {
			map.remove(u.key());
			if (map.isEmpty()) {
				byTable.remove(u.table());
			}
		} else {
			map.put(u.key(), u.priorOrNull());
		}
	}

	private void rebuildParticipations() {
		participations.clear();
		for (Map.Entry<String, Map<KeyWrapper, DirtyEntry>> e : byTable.entrySet()) {
			for (DirtyEntry d : e.getValue().values()) {
				participations.add(new Participation(e.getKey(), d.shard()));
			}
		}
	}

	private static String normalizeName(String name) {
		Objects.requireNonNull(name, "savepoint name");
		if (name.isBlank()) {
			throw new IllegalArgumentException("savepoint name required");
		}
		return name.toLowerCase(Locale.ROOT);
	}
}