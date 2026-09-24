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
package org.genfork.grid.sql.exec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.ast.SelectAst.JoinEdge;
import org.genfork.grid.sql.ast.SelectAst.JoinKind;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.genfork.grid.sql.tx.DistForUpdateCoordinator;
import org.genfork.grid.sql.tx.DistForUpdatePeerLockAgent;
import org.genfork.grid.sql.tx.DistForUpdatePeerLockLease;
import org.genfork.grid.sql.tx.KeyWrapper;

/**
 * Multi-table {@code FOR UPDATE} lock helpers for INNER JOIN (wire PK keys only).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlForUpdateJoinLockOps {
	private static final int LENGTH_BYTES = Integer.BYTES;
	private static final ByteOrder WIRE_BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;
	private static final String MSG_JOIN_FOR_UPDATE =
			"FOR UPDATE on JOIN requires INNER JOIN without SKIP LOCKED / aggregate / window";

	private SqlForUpdateJoinLockOps() {
	}

	/**
	 * Validate that {@code FOR UPDATE} + JOIN is allowed for this statement.
	 *
	 * @throws IllegalArgumentException when unsupported
	 */
	public static void requireSupportedJoinForUpdate(SelectSql s) {
		if (!s.forUpdate() || !s.hasJoins()) {
			return;
		}
		if (s.skipLocked() || s.aggregate() || s.hasWindow()
				|| s.hasFromFunction() || SqlProjectionOps.hasFunctionProjection(s)) {
			throw new IllegalArgumentException(MSG_JOIN_FOR_UPDATE);
		}
		for (JoinEdge edge : s.joins()) {
			if (edge.kind() != JoinKind.INNER) {
				throw new IllegalArgumentException(MSG_JOIN_FOR_UPDATE);
			}
		}
	}

	/**
	 * Per-table statement locks and peer leases acquired for a join FOR UPDATE.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record MultiTableLockBatch(
			List<TableLocks> tables
	) {
		public record TableLocks(
				String table,
				List<byte[]> statementLocks,
				List<DistForUpdatePeerLockLease> peerStatementLocks
		) {
		}
	}

	/**
	 * Collect distinct map keys per join side from value blobs, then lock locally + peers.
	 */
	public static MultiTableLockBatch acquireJoinLocks(
			SqlSession session,
			List<String> sideTables,
			List<TableSchema> sideSchemas,
			List<SqlJoinOps.JoinBlobRow> rows,
			List<DistForUpdatePeerLockAgent> agents
	) {
		final Map<String, List<byte[]>> keysByTable = collectKeysByTable(sideTables, sideSchemas, rows);
		final List<MultiTableLockBatch.TableLocks> acquired = new ArrayList<>(keysByTable.size());
		try {
			for (Map.Entry<String, List<byte[]>> entry : keysByTable.entrySet()) {
				final String table = entry.getKey();
				final List<byte[]> statementLocks = new ArrayList<>(entry.getValue().size());
				for (byte[] key : entry.getValue()) {
					session.lockManager().lock(table, key);
					statementLocks.add(key);
					if (session.inTransaction()) {
						session.requireTx().rememberLock(table, new KeyWrapper(key));
					}
				}
				final DistForUpdateCoordinator.PeerLockBatch peerBatch = DistForUpdateCoordinator.acquirePeerLocks(
						session,
						table,
						entry.getValue(),
						statementLocks,
						false,
						agents,
						session.envelopeCoordinator()
				);
				acquired.add(new MultiTableLockBatch.TableLocks(
						table,
						statementLocks,
						session.inTransaction() ? List.of() : peerBatch.peerLeases()));
			}
			return new MultiTableLockBatch(List.copyOf(acquired));
		} catch (RuntimeException ex) {
			release(session, acquired);
			throw ex;
		}
	}

	public static void release(SqlSession session, MultiTableLockBatch batch) {
		if (batch == null) {
			return;
		}
		release(session, batch.tables());
	}

	private static void release(SqlSession session, List<MultiTableLockBatch.TableLocks> tables) {
		if (tables == null) {
			return;
		}
		for (MultiTableLockBatch.TableLocks locks : tables) {
			if (!session.inTransaction()) {
				for (byte[] key : locks.statementLocks()) {
					session.lockManager().unlock(locks.table(), key);
				}
			}
			DistForUpdateCoordinator.releaseStatementPeerLocks(locks.peerStatementLocks());
		}
	}

	private static Map<String, List<byte[]>> collectKeysByTable(
			List<String> sideTables,
			List<TableSchema> sideSchemas,
			List<SqlJoinOps.JoinBlobRow> rows
	) {
		final Map<String, List<byte[]>> out = new LinkedHashMap<>();
		final Map<String, List<byte[]>> dedup = new LinkedHashMap<>();
		for (int side = 0; side < sideTables.size(); side++) {
			dedup.put(sideTables.get(side), new ArrayList<>());
		}
		for (SqlJoinOps.JoinBlobRow row : rows) {
			final byte[][] sides = row.sides();
			for (int side = 0; side < sideTables.size() && side < sides.length; side++) {
				final byte[] blob = sides[side];
				if (blob == null) {
					continue;
				}
				final String table = sideTables.get(side);
				final byte[] key = mapKeyFromValueBlob(sideSchemas.get(side), blob);
				final List<byte[]> list = dedup.get(table);
				if (!containsKey(list, key)) {
					list.add(key);
				}
			}
		}
		for (Map.Entry<String, List<byte[]>> entry : dedup.entrySet()) {
			if (!entry.getValue().isEmpty()) {
				out.put(entry.getKey(), List.copyOf(entry.getValue()));
			}
		}
		return out;
	}

	private static boolean containsKey(List<byte[]> keys, byte[] key) {
		for (byte[] existing : keys) {
			if (Arrays.equals(existing, key)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Map key bytes from a stored row value blob (same layout as {@code PrimaryKeyCodec}).
	 */
	static byte[] mapKeyFromValueBlob(TableSchema schema, byte[] valueBlob) {
		final List<ColumnDef> pkColumns = schema.pkColumns();
		final LogicalFieldCursor cursor = LogicalFieldCursor.open(schema, valueBlob);
		if (pkColumns.size() == 1) {
			return cursor.indexKeyBytes(pkColumns.getFirst().ordinal());
		}
		final byte[][] components = new byte[pkColumns.size()][];
		int totalBytes = 0;
		for (int i = 0; i < pkColumns.size(); i++) {
			components[i] = cursor.indexKeyBytes(pkColumns.get(i).ordinal());
			totalBytes = Math.addExact(totalBytes, Math.addExact(LENGTH_BYTES, components[i].length));
		}
		final ByteBuffer out = ByteBuffer.allocate(totalBytes).order(WIRE_BYTE_ORDER);
		for (byte[] component : components) {
			out.putInt(component.length);
			out.put(component);
		}
		return out.array();
	}
}
