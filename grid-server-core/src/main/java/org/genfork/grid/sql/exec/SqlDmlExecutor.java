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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.FkAction;
import org.genfork.grid.catalog.FkDef;
import org.genfork.grid.catalog.SequenceDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableAnalyzeStats;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.catalog.TriggerEvent;
import org.genfork.grid.mem.index.GridCompositeIndex;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.ast.DmlAst.AnalyzeSql;
import org.genfork.grid.sql.ast.DmlAst.ConflictAction;
import org.genfork.grid.sql.ast.DmlAst.DeleteSql;
import org.genfork.grid.sql.ast.DmlAst.InsertSql;
import org.genfork.grid.sql.ast.DmlAst.MergeSql;
import org.genfork.grid.sql.ast.DmlAst.OnConflict;
import org.genfork.grid.sql.ast.DmlAst.SequenceCallExpr;
import org.genfork.grid.sql.ast.DmlAst.UpdateSql;
import org.genfork.grid.sql.SqlStatementTag;
import org.genfork.grid.sql.tx.KeyWrapper;
import org.genfork.grid.sql.tx.SqlTxBuffer;
import org.genfork.grid.store.TableStore;
import org.genfork.grid.utils.SerialUtil;

/**
 * INSERT / UPDATE / DELETE / MERGE (+ TX staging) and {@code ANALYZE} for {@link org.genfork.grid.sql.SqlEngine}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlDmlExecutor {
	/**
	 * Soft cap for {@code MERGE â¦ USING table} source scan (fail closed if exceeded).
	 */
	private static final int MAX_MERGE_SOURCE_ROWS = 1_000_000;
	private static final int MAX_UPDATE_FROM_SOURCE_KEYS = 1_000_000;
	private static final int HASH_MAP_MIN_CAPACITY = 16;

	private final SqlTableResolver tables;
	private final SqlEngine engine;

	public SqlDmlExecutor(SqlTableResolver tables, SqlEngine engine) {
		this.tables = tables;
		this.engine = engine;
	}

	public SqlResult insert(SqlSession session, InsertSql s) {
		final String table = tables.resolveTable(session, s.table());
		final TableStore store = tables.requireStore(table);
		SqlTriggerFireOps.fireBeforeStatement(session, engine, table, TriggerEvent.INSERT);
		final List<String> colNames = s.columns().isEmpty()
				? store.schema().columns().stream().map(ColumnDef::name).toList()
				: s.columns();
		final OnConflict conflict = s.onConflictOrNull();
		final List<byte[]> returned = new ArrayList<>();
		long affected = 0L;
		for (List<Object> lits : s.rows()) {
			if (lits.size() != colNames.size()) {
				throw new IllegalArgumentException("INSERT column/value count mismatch");
			}
			final Map<String, Object> named = new LinkedHashMap<>();
			for (int i = 0; i < colNames.size(); i++) {
				named.put(colNames.get(i), resolveInsertValue(session, lits.get(i)));
			}
			fillIdentityDefaults(session, store.schema(), named);
			final Object[] row = store.rowFromNamed(named);
			assertFkParents(session, table, row);
			final long rowAffected = applyInsertRow(session, table, store, row, conflict);
			affected += rowAffected;
			if (rowAffected > 0L && !s.returning().isEmpty()) {
				final byte[] key = conflict == null
						? store.keyBytesForPk(row[store.schema().pkColumn().ordinal()])
						: SqlMergeMatchOps.resolveConflictKey(store, row, conflict);
				final byte[] value = SqlDmlLockOps.existingBytes(session, table, store, key);
				if (value != null) {
					returned.add(value);
				}
			}
		}
		final SqlResult result = !s.returning().isEmpty()
				? SqlDmlReturningOps.returningResult(store, SqlStatementTag.INSERT, s.returning(), returned)
				: SqlResult.affected(SqlStatementTag.INSERT, affected);
		SqlTriggerFireOps.fireAfterStatement(session, engine, table, TriggerEvent.INSERT);
		return result;
	}

	private Object resolveInsertValue(SqlSession session, Object raw) {
		if (raw instanceof SequenceCallExpr call) {
			final String seq = tables.resolveTable(session, call.sequenceName());
			if (call.nextVal()) {
				final long v = tables.catalog().nextVal(seq);
				session.rememberSequenceValue(seq, v);
				return Long.valueOf(v);
			}
			return Long.valueOf(session.currval(seq));
		}
		return raw;
	}

	private void fillIdentityDefaults(SqlSession session, TableSchema schema, Map<String, Object> named) {
		for (ColumnDef col : schema.columns()) {
			if (!col.identity()) {
				continue;
			}
			if (SqlMergeMatchOps.namedHasNonNull(named, col.name())) {
				continue;
			}
			final String seq = col.identitySequence() != null
					? col.identitySequence()
					: SequenceDef.identityName(schema.tableName(), col.name());
			final long v = tables.catalog().nextVal(seq);
			session.rememberSequenceValue(seq, v);
			if (col.type() == SqlType.INT) {
				named.put(col.name(), Integer.valueOf((int) v));
			} else {
				named.put(col.name(), Long.valueOf(v));
			}
		}
	}

	private void assertFkParents(SqlSession session, String childTable, Object[] row) {
		final TableSchema schema = tables.requireStore(childTable).schema();
		for (FkDef fk : schema.foreignKeys()) {
			final List<Object> childValues = new ArrayList<>(fk.childColumns().size());
			boolean anyNull = false;
			for (String childColumn : fk.childColumns()) {
				final Object childValue = row[schema.requireColumn(childColumn).ordinal()];
				childValues.add(childValue);
				anyNull |= childValue == null;
			}
			if (anyNull) {
				continue;
			}
			final TableStore parent = tables.requireStore(fk.parentTable());
			final List<String> parentPkColumns =
					parent.schema().pkColumns().stream().map(ColumnDef::name).toList();
			final byte[] parentKey = SqlMergeMatchOps.columnsEqualIgnoreCase(parentPkColumns, fk.parentColumns())
					? parent.keyBytesForPk(childValues.size() == 1
							? childValues.getFirst()
							: childValues.toArray())
					: SqlMergeMatchOps.resolveMatchKey(parent, fk.parentColumns(), childValues);
			if (SqlDmlLockOps.existingBytes(session, fk.parentTable(), parent, parentKey) == null) {
				throw new IllegalStateException("FOREIGN KEY violation: parent key not found (" + fk.name() + ")");
			}
		}
	}

	private void enforceParentDelete(SqlSession session, String parentTable, Object pkValue, byte[] parentKey) {
		final TableStore parent = tables.requireStore(parentTable);
		final byte[] parentValue = SqlDmlLockOps.existingBytes(session, parentTable, parent, parentKey);
		if (parentValue == null || !LogicalFieldCursor.canOpen(parent.schema(), parentValue)) {
			return;
		}
		final LogicalFieldCursor parentCursor = LogicalFieldCursor.open(parent.schema(), parentValue);
		for (FkDef fk : tables.catalog().foreignKeysReferencing(parentTable)) {
			final TableStore child = tables.requireStore(fk.childTable());
			final TableSchema childSchema = child.schema();
			final byte[][] want = new byte[fk.parentColumns().size()][];
			for (int i = 0; i < fk.parentColumns().size(); i++) {
				want[i] = parentCursor.indexKeyBytes(
						parent.schema().requireColumn(fk.parentColumns().get(i)).ordinal());
			}
			if (fk.onDelete() == FkAction.RESTRICT) {
				SqlFkMatchOps.forEachChildFkMatch(child, childSchema, fk, want, (key, value, cursor) -> {
					throw new IllegalStateException(
							"FOREIGN KEY RESTRICT: parent row still referenced (" + fk.name() + ")");
				});
			} else if (fk.onDelete() == FkAction.CASCADE) {
				SqlFkMatchOps.forEachChildFkMatch(child, childSchema, fk, want, (key, value, cursor) -> {
					if (session.inTransaction()) {
						SqlDmlLockOps.stage(session, fk.childTable(), SqlTxBuffer.Op.DELETE,
								new TableStore.EncodedRow(key, null, child.shardOf(key)));
					} else {
						child.deleteByKeyBytes(key);
					}
				});
			} else if (fk.onDelete() == FkAction.SET_NULL) {
				for (String childColumn : fk.childColumns()) {
					if (!childSchema.requireColumn(childColumn).nullable()) {
						throw new IllegalStateException(
								"FOREIGN KEY SET NULL requires nullable child columns (" + fk.name() + ")");
					}
				}
				SqlFkMatchOps.forEachChildFkMatch(child, childSchema, fk, want, (key, value, cursor) -> {
					final Map<String, Object> sets = new LinkedHashMap<>();
					for (String childColumn : fk.childColumns()) {
						sets.put(childColumn, null);
					}
					if (session.inTransaction()) {
						SqlDmlLockOps.stage(session, fk.childTable(), SqlTxBuffer.Op.UPSERT,
								child.encodeSetLiterals(key, value, sets));
					} else {
						child.updateSetLiteralsByKey(key, sets);
					}
				});
			}
		}
	}

	/**
	 * {@code ANALYZE table}: collect crude row/fan-out hints and persist beside catalog.
	 */
	public SqlResult analyze(SqlSession session, AnalyzeSql s) {
		final String table = tables.resolveTable(session, s.table());
		final TableStore store = tables.requireStore(table);
		final TableAnalyzeStats stats = store.analyze();
		tables.catalog().putAnalyzeStats(table, stats);
		return SqlResult.affected(SqlStatementTag.ANALYZE, stats.rowCount());
	}

	/**
	 * {@code MERGE INTO â¦}: conflict on ON equality (PK / unique wire keys) â' UPDATE or INSERT.
	 * <p>
	 * {@code USING (VALUES â¦)} keeps the single-row literal path. {@code USING table} scans
	 * the source store and, for NOT MATCHED INSERT, prefers column values from each source
	 * row (by insert-column name) over the statement VALUES placeholders.
	 */
	public SqlResult merge(SqlSession session, MergeSql s) {
		final String table = tables.resolveTable(session, s.targetTable());
		final TableStore store = tables.requireStore(table);
		if (s.sourceTableOrNull() != null) {
			return mergeUsingTable(session, table, store, s);
		}
		if (s.sourceRowOrNull() == null || s.sourceRowOrNull().isEmpty()) {
			throw new IllegalArgumentException("MERGE USING VALUES required");
		}
		final TableSchema schema = store.schema();
		final List<String> sourceCols = schema.columns().stream().map(ColumnDef::name).toList();
		if (s.sourceRowOrNull().size() != sourceCols.size()) {
			throw new IllegalArgumentException(
					"MERGE USING VALUES arity must match table columns (" + sourceCols.size() + ")");
		}
		final Map<String, Object> sourceNamed = new LinkedHashMap<>();
		for (int i = 0; i < sourceCols.size(); i++) {
			sourceNamed.put(sourceCols.get(i), s.sourceRowOrNull().get(i));
		}
		return SqlResult.affected(
				SqlStatementTag.MERGE,
				mergeOneSourceRow(session, table, store, s, sourceNamed, sourceCols, false));
	}

	private SqlResult mergeUsingTable(SqlSession session, String table, TableStore store, MergeSql s) {
		final String sourceName = tables.resolveTable(session, s.sourceTableOrNull());
		final TableStore sourceStore = tables.requireStore(sourceName);
		final TableSchema sourceSchema = sourceStore.schema();
		final List<String> sourceCols = sourceSchema.columns().stream().map(ColumnDef::name).toList();
		final List<Object[]> sourceRows = new ArrayList<>();
		sourceStore.forEachPrimaryKey(key -> {
			if (sourceRows.size() >= MAX_MERGE_SOURCE_ROWS) {
				throw new IllegalArgumentException(
						"MERGE USING table exceeds MAX_MERGE_SOURCE_ROWS=" + MAX_MERGE_SOURCE_ROWS);
			}
			final byte[] value = sourceStore.getCommittedBytes(key);
			if (value == null || !LogicalFieldCursor.canOpen(sourceSchema, value)) {
				return;
			}
			sourceRows.add(LogicalFieldCursor.open(sourceSchema, value).project(null));
		});
		long affected = 0L;
		for (Object[] projected : sourceRows) {
			final Map<String, Object> sourceNamed = new LinkedHashMap<>();
			for (int i = 0; i < sourceCols.size(); i++) {
				sourceNamed.put(sourceCols.get(i), projected[i]);
			}
			affected += mergeOneSourceRow(session, table, store, s, sourceNamed, sourceCols, true);
		}
		return SqlResult.affected(SqlStatementTag.MERGE, affected);
	}

	/**
	 * Apply MATCHED UPDATE / NOT MATCHED INSERT for one source named map.
	 *
	 * @param fromTable when true, INSERT values are taken from {@code sourceNamed} by column name
	 *                  (statement VALUES act as arity / fallback only)
	 */
	private long mergeOneSourceRow(
			SqlSession session,
			String table,
			TableStore store,
			MergeSql s,
			Map<String, Object> sourceNamed,
			List<String> defaultInsertCols,
			boolean fromTable
	) {
		if (!SqlMergeMatchOps.sourceNamedContains(sourceNamed, s.sourceOnCol())) {
			throw new IllegalArgumentException("MERGE ON source column unknown: " + s.sourceOnCol());
		}
		final Object matchValue = SqlMergeMatchOps.lookupNamed(sourceNamed, s.sourceOnCol());
		final byte[] conflictKey = SqlMergeMatchOps.resolveMatchKey(store, s.targetOnCol(), List.of(matchValue));
		final byte[] existing = SqlDmlLockOps.existingBytes(session, table, store, conflictKey);
		if (existing != null) {
			if (s.matchedSetsOrNull() == null || s.matchedSetsOrNull().isEmpty()) {
				return 0L;
			}
			return applyConflictUpdate(session, table, store, conflictKey, existing, s.matchedSetsOrNull());
		}
		if (s.insertValuesOrNull() == null) {
			return 0L;
		}
		final List<String> insertCols = s.insertColumnsOrNull() == null || s.insertColumnsOrNull().isEmpty()
				? defaultInsertCols
				: s.insertColumnsOrNull();
		final Map<String, Object> named = new LinkedHashMap<>();
		if (fromTable) {
			if (s.insertValuesOrNull() != null && s.insertValuesOrNull().size() != insertCols.size()) {
				throw new IllegalArgumentException("MERGE INSERT column/value count mismatch");
			}
			for (int i = 0; i < insertCols.size(); i++) {
				final String col = insertCols.get(i);
				if (SqlMergeMatchOps.sourceNamedContains(sourceNamed, col)) {
					named.put(col, SqlMergeMatchOps.lookupNamed(sourceNamed, col));
				} else if (s.insertValuesOrNull() != null) {
					named.put(col, s.insertValuesOrNull().get(i));
				} else {
					throw new IllegalArgumentException(
							"MERGE INSERT column missing on source row: " + col);
				}
			}
		} else {
			if (s.insertValuesOrNull().size() != insertCols.size()) {
				throw new IllegalArgumentException("MERGE INSERT column/value count mismatch");
			}
			for (int i = 0; i < insertCols.size(); i++) {
				named.put(insertCols.get(i), s.insertValuesOrNull().get(i));
			}
		}
		final Object[] row = store.rowFromNamed(named);
		return applyInsertRow(session, table, store, row, null);
	}

	public SqlResult delete(SqlSession session, DeleteSql s) {
		final String table = tables.resolveTable(session, s.table());
		final TableStore store = tables.requireStore(table);
		SqlTriggerFireOps.fireBeforeStatement(session, engine, table, TriggerEvent.DELETE);
		final String pkName = store.schema().pkColumn().name();
		if (!session.inTransaction()) {
			if (s.pkColumnOrNull() != null && pkName.equalsIgnoreCase(s.pkColumnOrNull())) {
				final byte[] key = store.keyBytesForPk(s.pkValueOrNull());
				final byte[] oldBlob = SqlDmlLockOps.existingBytes(session, table, store, key);
				SqlTriggerFireOps.fireBeforeRow(session, engine, table, TriggerEvent.DELETE, oldBlob, null);
				enforceParentDelete(session, table, s.pkValueOrNull(), key);
				final long affected = store.deleteByPk(s.pkValueOrNull());
				SqlTriggerFireOps.fireAfterRow(session, engine, table, TriggerEvent.DELETE, oldBlob, null);
				final SqlResult result = SqlResult.affected(SqlStatementTag.DELETE, affected);
				SqlTriggerFireOps.fireAfterStatement(session, engine, table, TriggerEvent.DELETE);
				return result;
			}
			long affected = 0L;
			for (byte[] key : store.keysMatching(s.whereSql())) {
				final byte[] oldBlob = SqlDmlLockOps.existingBytes(session, table, store, key);
				SqlTriggerFireOps.fireBeforeRow(session, engine, table, TriggerEvent.DELETE, oldBlob, null);
				final Object pkObj = SerialUtil.readPrimitives(
						key, 0, store.schema().pkColumn().javaType());
				enforceParentDelete(session, table, pkObj, key);
				affected += store.deleteByKeyBytes(key);
				SqlTriggerFireOps.fireAfterRow(session, engine, table, TriggerEvent.DELETE, oldBlob, null);
			}
			final SqlResult result = SqlResult.affected(SqlStatementTag.DELETE, affected);
			SqlTriggerFireOps.fireAfterStatement(session, engine, table, TriggerEvent.DELETE);
			return result;
		}
		long affected = 0L;
		if (s.pkColumnOrNull() != null && pkName.equalsIgnoreCase(s.pkColumnOrNull())) {
			final byte[] key = store.keyBytesForPk(s.pkValueOrNull());
			final byte[] oldBlob = SqlDmlLockOps.existingBytes(session, table, store, key);
			SqlTriggerFireOps.fireBeforeRow(session, engine, table, TriggerEvent.DELETE, oldBlob, null);
			enforceParentDelete(session, table, s.pkValueOrNull(), key);
			SqlDmlLockOps.stage(session, table, SqlTxBuffer.Op.DELETE, store.encodeDeletePk(s.pkValueOrNull()));
			SqlTriggerFireOps.fireAfterRow(session, engine, table, TriggerEvent.DELETE, oldBlob, null);
			affected = 1L;
		} else {
			for (byte[] key : store.keysMatching(s.whereSql())) {
				final byte[] oldBlob = SqlDmlLockOps.existingBytes(session, table, store, key);
				SqlTriggerFireOps.fireBeforeRow(session, engine, table, TriggerEvent.DELETE, oldBlob, null);
				final Object pkObj = SerialUtil.readPrimitives(
						key, 0, store.schema().pkColumn().javaType());
				enforceParentDelete(session, table, pkObj, key);
				SqlDmlLockOps.stage(session, table, SqlTxBuffer.Op.DELETE,
						new TableStore.EncodedRow(key, null, store.shardOf(key)));
				SqlTriggerFireOps.fireAfterRow(session, engine, table, TriggerEvent.DELETE, oldBlob, null);
				affected++;
			}
		}
		final SqlResult result = SqlResult.affected(SqlStatementTag.DELETE, affected);
		SqlTriggerFireOps.fireAfterStatement(session, engine, table, TriggerEvent.DELETE);
		return result;
	}

	public SqlResult update(SqlSession session, UpdateSql s) {
		final String table = tables.resolveTable(session, s.table());
		final TableStore store = tables.requireStore(table);
		SqlTriggerFireOps.fireBeforeStatement(session, engine, table, TriggerEvent.UPDATE);
		if (s.sourceTableOrNull() != null) {
			final SqlResult result = updateFrom(session, table, store, s);
			SqlTriggerFireOps.fireAfterStatement(session, engine, table, TriggerEvent.UPDATE);
			return result;
		}
		final String pkName = store.schema().pkColumn().name();
		final List<byte[]> returningKeys = s.returning().isEmpty()
				? List.of()
				: SqlDmlReturningOps.updateKeys(store, s, pkName);
		if (!session.inTransaction()) {
			if (s.rmw() != null) {
				final byte[] key = store.keyBytesForPk(s.rmw().pkValue());
				session.lockManager().lock(table, key);
				try {
					final byte[] base = store.getCommittedBytes(key);
					final TableStore.EncodedRow encoded =
							store.encodeUpdatePlan(s.rmw(), base, s.setLiterals());
					SqlTriggerFireOps.fireBeforeRow(
							session, engine, table, TriggerEvent.UPDATE, base, encoded.valueBytes());
					final long affected = store.installEncodedUpsert(encoded);
					final byte[] newBlob = store.getCommittedBytes(key);
					SqlTriggerFireOps.fireAfterRow(
							session, engine, table, TriggerEvent.UPDATE, base, newBlob);
					final SqlResult result =
							SqlDmlReturningOps.updateResult(session, table, store, s, returningKeys, affected);
					SqlTriggerFireOps.fireAfterStatement(session, engine, table, TriggerEvent.UPDATE);
					return result;
				} finally {
					session.lockManager().unlock(table, key);
				}
			}
			if (s.pkColumnOrNull() != null && pkName.equalsIgnoreCase(s.pkColumnOrNull())) {
				final byte[] key = store.keyBytesForPk(s.pkValueOrNull());
				session.lockManager().lock(table, key);
				try {
					final long affected = updateOneKey(
							session, table, store, key, s.setLiterals(), false);
					final SqlResult result =
							SqlDmlReturningOps.updateResult(session, table, store, s, returningKeys, affected);
					SqlTriggerFireOps.fireAfterStatement(session, engine, table, TriggerEvent.UPDATE);
					return result;
				} finally {
					session.lockManager().unlock(table, key);
				}
			}
			final Map<String, Object> sets = s.setLiterals();
			final long affected = store.forEachMatchingKey(s.whereSql(), key -> {
						session.lockManager().lock(table, key);
						try {
							return updateOneKey(session, table, store, key, sets, false);
						} finally {
							session.lockManager().unlock(table, key);
						}
					});
			final SqlResult result =
					SqlDmlReturningOps.updateResult(session, table, store, s, returningKeys, affected);
			SqlTriggerFireOps.fireAfterStatement(session, engine, table, TriggerEvent.UPDATE);
			return result;
		}
		long affected = 0L;
		if (s.rmw() != null) {
			final byte[] key = store.keyBytesForPk(s.rmw().pkValue());
			SqlDmlLockOps.ensureRowLocked(session, table, key);
			final byte[] base = SqlDmlLockOps.baseBytes(session, table, store, key);
			final TableStore.EncodedRow encoded =
					store.encodeUpdatePlan(s.rmw(), base, s.setLiterals());
			SqlTriggerFireOps.fireBeforeRow(
					session, engine, table, TriggerEvent.UPDATE, base, encoded.valueBytes());
			SqlDmlLockOps.stage(session, table, SqlTxBuffer.Op.UPSERT, encoded);
			SqlTriggerFireOps.fireAfterRow(
					session, engine, table, TriggerEvent.UPDATE, base, encoded.valueBytes());
			affected = 1L;
		} else if (s.pkColumnOrNull() != null && pkName.equalsIgnoreCase(s.pkColumnOrNull())) {
			final byte[] key = store.keyBytesForPk(s.pkValueOrNull());
			affected = updateOneKey(session, table, store, key, s.setLiterals(), true);
		} else {
			for (byte[] key : store.keysMatching(s.whereSql())) {
				affected += updateOneKey(session, table, store, key, s.setLiterals(), true);
			}
		}
		final SqlResult result =
				SqlDmlReturningOps.updateResult(session, table, store, s, returningKeys, affected);
		SqlTriggerFireOps.fireAfterStatement(session, engine, table, TriggerEvent.UPDATE);
		return result;
	}

	private long updateOneKey(
			SqlSession session,
			String table,
			TableStore store,
			byte[] key,
			Map<String, Object> sets,
			boolean stageInTx
	) {
		final byte[] base = stageInTx
				? SqlDmlLockOps.baseBytes(session, table, store, key)
				: SqlDmlLockOps.existingBytes(session, table, store, key);
		final TableStore.EncodedRow encoded = store.encodeSetLiterals(key, base, sets);
		SqlTriggerFireOps.fireBeforeRow(
				session, engine, table, TriggerEvent.UPDATE, base, encoded.valueBytes());
		final long affected;
		if (stageInTx) {
			SqlDmlLockOps.ensureRowLocked(session, table, key);
			SqlDmlLockOps.stage(session, table, SqlTxBuffer.Op.UPSERT, encoded);
			affected = 1L;
		} else {
			affected = store.updateSetLiteralsByKey(key, sets);
		}
		if (affected > 0L) {
			SqlTriggerFireOps.fireAfterRow(
					session, engine, table, TriggerEvent.UPDATE, base, encoded.valueBytes());
		}
		return affected;
	}

	private SqlResult updateFrom(SqlSession session, String table, TableStore store, UpdateSql s) {
		final String sourceTable = tables.resolveTable(session, s.sourceTableOrNull());
		final TableStore source = tables.requireStore(sourceTable);
		final TableSchema sourceSchema = source.schema();
		final TableSchema targetSchema = store.schema();
		final int sourceOrdinal = sourceSchema.requireColumn(s.sourceJoinColumnOrNull()).ordinal();
		final String targetJoinCol = s.targetJoinColumnOrNull();
		final String targetPk = targetSchema.pkColumn().name();
		final Set<KeyWrapper> sourceKeys = new HashSet<>(HASH_MAP_MIN_CAPACITY);
		source.forEachPrimaryKey(rowKey -> {
			if (sourceKeys.size() >= MAX_UPDATE_FROM_SOURCE_KEYS) {
				throw new IllegalArgumentException(
						"UPDATE FROM exceeds MAX_UPDATE_FROM_SOURCE_KEYS=" + MAX_UPDATE_FROM_SOURCE_KEYS);
			}
			final byte[] value = source.getCommittedBytes(rowKey);
			if (value == null || !LogicalFieldCursor.canOpen(sourceSchema, value)) {
				return;
			}
			final byte[] joinKey = LogicalFieldCursor.open(sourceSchema, value).indexKeyBytes(sourceOrdinal);
			if (joinKey != null) {
				sourceKeys.add(new KeyWrapper(joinKey));
			}
		});
		final List<byte[]> targetKeys = new ArrayList<>();
		if (targetPk.equalsIgnoreCase(targetJoinCol)) {
			for (KeyWrapper join : sourceKeys) {
				final byte[] targetKey = join.key();
				if (store.getCommittedBytes(targetKey) != null) {
					targetKeys.add(targetKey);
				}
			}
		} else if (store.hasEqIndex(List.of(targetJoinCol))) {
			for (KeyWrapper join : sourceKeys) {
				store.forEachEqKey(List.of(targetJoinCol), new byte[][]{join.key()}, targetKeys::add);
			}
		} else {
			throw new IllegalArgumentException(
					GridCompositeIndex.MSG_EQ_REQUIRES_INDEX + ": UPDATE FROM target join " + targetJoinCol);
		}
		long affected = 0L;
		for (byte[] key : targetKeys) {
			if (session.inTransaction()) {
				affected += updateOneKey(session, table, store, key, s.setLiterals(), true);
			} else {
				session.lockManager().lock(table, key);
				try {
					affected += updateOneKey(session, table, store, key, s.setLiterals(), false);
				} finally {
					session.lockManager().unlock(table, key);
				}
			}
		}
		return SqlDmlReturningOps.updateResult(session, table, store, s, targetKeys, affected);
	}

	private long applyInsertRow(
			SqlSession session,
			String table,
			TableStore store,
			Object[] row,
			OnConflict conflict
	) {
		if (conflict == null) {
			final TableStore.EncodedRow encoded = store.encodeUpsert(row, true);
			SqlTriggerFireOps.fireBeforeRow(
					session, engine, table, TriggerEvent.INSERT, null, encoded.valueBytes());
			final long affected;
			if (session.inTransaction()) {
				SqlDmlLockOps.stage(session, table, SqlTxBuffer.Op.UPSERT, encoded);
				affected = 1L;
			} else {
				affected = store.upsert(row);
			}
			if (affected > 0L) {
				SqlTriggerFireOps.fireAfterRow(
						session, engine, table, TriggerEvent.INSERT, null, encoded.valueBytes());
			}
			return affected;
		}
		// Resolve conflict before encodeUpsert — UNIQUE on target columns must not reject DO UPDATE hit.
		final byte[] conflictKey = SqlMergeMatchOps.resolveConflictKey(store, row, conflict);
		final byte[] existing;
		if (conflictKey != null) {
			SqlDmlLockOps.ensureConflictKeyLocked(session, table, conflictKey);
			existing = SqlDmlLockOps.existingBytes(session, table, store, conflictKey);
		} else {
			existing = null;
		}
		if (existing != null) {
			if (conflict.action() == ConflictAction.DO_NOTHING) {
				return 0L;
			}
			if (conflict.action() == ConflictAction.DO_UPSERT_VALUES) {
				final TableStore.EncodedRow encoded = store.encodeUpsert(row, false);
				SqlTriggerFireOps.fireBeforeRow(
						session, engine, table, TriggerEvent.INSERT, null, encoded.valueBytes());
				final long affected;
				if (session.inTransaction()) {
					SqlDmlLockOps.stage(session, table, SqlTxBuffer.Op.UPSERT, encoded);
					affected = 1L;
				} else {
					affected = store.upsert(row, false);
				}
				if (affected > 0L) {
					SqlTriggerFireOps.fireAfterRow(
							session, engine, table, TriggerEvent.INSERT, null, encoded.valueBytes());
				}
				return affected;
			}
			return applyConflictUpdate(session, table, store, conflictKey, existing, conflict.updateSets());
		}
		// Miss: insert new row (STRICT unique still enforced; conflict target has no match).
		final TableStore.EncodedRow encoded = store.encodeUpsert(row, false);
		SqlTriggerFireOps.fireBeforeRow(
				session, engine, table, TriggerEvent.INSERT, null, encoded.valueBytes());
		final long affected;
		if (session.inTransaction()) {
			SqlDmlLockOps.stage(session, table, SqlTxBuffer.Op.UPSERT, encoded);
			affected = 1L;
		} else {
			affected = store.upsert(row, false);
		}
		if (affected > 0L) {
			SqlTriggerFireOps.fireAfterRow(
					session, engine, table, TriggerEvent.INSERT, null, encoded.valueBytes());
		}
		return affected;
	}

	private long applyConflictUpdate(
			SqlSession session,
			String table,
			TableStore store,
			byte[] key,
			byte[] existing,
			Map<String, Object> sets
	) {
		if (sets == null || sets.isEmpty()) {
			throw new IllegalArgumentException("DO UPDATE / WHEN MATCHED requires SET assignments");
		}
		final TableStore.EncodedRow encoded = store.encodeSetLiterals(key, existing, sets);
		SqlTriggerFireOps.fireBeforeRow(
				session, engine, table, TriggerEvent.UPDATE, existing, encoded.valueBytes());
		final long affected;
		if (session.inTransaction()) {
			SqlDmlLockOps.stage(session, table, SqlTxBuffer.Op.UPSERT, encoded);
			affected = 1L;
		} else {
			affected = store.updateSetLiteralsByKey(key, sets);
		}
		if (affected > 0L) {
			SqlTriggerFireOps.fireAfterRow(
					session, engine, table, TriggerEvent.UPDATE, existing, encoded.valueBytes());
		}
		return affected;
	}
}
