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

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.FunctionKind;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableCatalog.FunctionDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.GridCompositeIndex;
import org.genfork.grid.query.distributed.DistributedKeyFanOut;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.filters.impl.AlwaysFalseCondition;
import org.genfork.grid.query.filters.impl.AlwaysTrueCondition;
import org.genfork.grid.query.filters.impl.LogicalOperatorCondition;
import org.genfork.grid.query.plan.QueryData;
import org.genfork.grid.query.plan.QueryParser;
import org.genfork.grid.query.util.QueryChunkMerge;
import org.genfork.grid.query.util.SetOpKind;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.serial.WireFieldBytes;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.ast.SelectAst.*;
import org.genfork.grid.sql.ast.Stmt;
import org.genfork.grid.sql.tx.*;
import org.genfork.grid.sql.udf.SqlUdfLookup;
import org.genfork.grid.store.TableStore;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * SELECT (+ multi INNER / LEFT / RIGHT / FULL OUTER JOIN, aggregates, window, WHERE/ORDER/LIMIT)
 * for {@link org.genfork.grid.sql.SqlEngine}.
 * <p>
 * Open-TX reads use snapshot = dirty overlay union committed (local tables). With
 * {@code grid.sql.distributed-peers}, read-only SELECT/JOIN may fan out to peers for
 * <em>committed</em> keys/blobs while the TX is open; dirty writes remain local until COMMIT
 * (no remote dirty). Peer suppliers see {@link DistTxSnapshot#currentOrZero()} while pinned.
 * {@code FOR UPDATE} with peers: local locks plus optional
 * {@link org.genfork.grid.sql.tx.DistForUpdatePeerLockAgent} fan-out; PREPARE/COMMIT peer votes
 * via {@link org.genfork.grid.sql.tx.DistForUpdatePrepareVotes} (see
 * {@link org.genfork.grid.sql.tx.DistForUpdateCoordinator}).
 * See {@link DistributedKeyFanOut} TX barrier.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlQueryExecutor {
	/**
	 * Locks released at statement end and keys selected after SKIP LOCKED.
	 */
	private record LockBatch(
			List<byte[]> statementLocks,
			List<byte[]> selectedKeys,
			List<DistForUpdatePeerLockLease> peerStatementLocks
	) {
	}
	/** Minimum HashMap / HashSet capacity hint for DISTINCT / JOIN / window maps. */
	private static final int HASH_MAP_MIN_CAPACITY = 16;

	private final SqlTableResolver tables;
	private final CrossDomainTableResolver domains;
	private final SqlExplainService explainService;
	private final ThreadLocal<List<FilterCondition>> subqueryFiltersTls = new ThreadLocal<>();

	public SqlQueryExecutor(SqlTableResolver tables) {
		this.tables = tables;
		this.domains = new CrossDomainTableResolver(tables);
		this.explainService = new SqlExplainService(tables, domains, this);
	}

	/** Cross-domain JOIN alias registry (shared with EXPLAIN). */
	public CrossDomainTableResolver domains() {
		return domains;
	}

	public SqlResult select(SqlSession session, SelectSql s) {
		subqueryFiltersTls.set(evaluateWhereSubqueries(session, s));
		try {
			return selectResolved(session, s);
		} finally {
			subqueryFiltersTls.remove();
		}
	}

	/**
	 * Execute {@code SELECT ... {UNION|INTERSECT|EXCEPT} [ALL] SELECT ...} via shared {@link QueryChunkMerge}.
	 */
	public SqlResult selectSetOp(SqlSession session, SetOpSql u) {
		if (u == null || u.arms() == null || u.arms().isEmpty()) {
			throw new IllegalArgumentException("set op requires at least one SELECT arm");
		}
		if (u.opsBetween() == null || u.opsBetween().size() != u.arms().size() - 1
				|| u.allBetween() == null || u.allBetween().size() != u.arms().size() - 1) {
			throw new IllegalArgumentException("set op arm/operator lists must align");
		}
		List<SqlResult.ColumnMeta> metas = null;
		List<Object[]> acc = null;
		for (int i = 0; i < u.arms().size(); i++) {
			final SqlResult arm = select(session, u.arms().get(i));
			if (metas == null) {
				metas = arm.columns();
				acc = new ArrayList<>(arm.rows());
				continue;
			}
			if (metas.size() != arm.columns().size()) {
				throw new IllegalArgumentException(
						"set-op arms must project the same number of columns");
			}
			final SetOpKind kind = u.opsBetween().get(i - 1);
			final boolean all = u.allBetween().get(i - 1);
			acc = QueryChunkMerge.applySetOp(acc, arm.rows(), kind, all, 0);
		}
		return SqlResult.resultSet(metas, acc == null ? List.of() : acc);
	}

	public SqlResult explainSetOp(SetOpSql u) {
		return explainService.explainSetOp(u);
	}

	public SqlResult explainAnalyzeSetOp(SqlSession session, SetOpSql u) {
		return explainService.explainAnalyzeSetOp(session, u);
	}

	public SqlResult explainStatement(SqlSession session, Stmt statement) {
		return explainService.explainStatement(session, statement);
	}

	private SqlResult selectResolved(SqlSession session, SelectSql s) {
		if (s.isExprOnly()) {
			return selectExprOnly(s);
		}
		if (s.forUpdate() && (s.aggregate() || s.hasWindow()
				|| s.hasFromFunction() || SqlProjectionOps.hasFunctionProjection(s))) {
			throw new IllegalArgumentException("FOR UPDATE supports a base-table or INNER JOIN SELECT in v1");
		}
		if (s.forUpdate() && s.hasJoins()) {
			SqlForUpdateJoinLockOps.requireSupportedJoinForUpdate(s);
		}
		if (s.hasFromFunction()) {
			return selectFromTableUdf(session, s);
		}
		if (SqlInformationSchemaExecutor.matches(s.table())) {
			final SqlResult raw = SqlInformationSchemaExecutor.select(tables.catalog(), s);
			List<Object[]> rows = applyWhere(s, raw.columns(), new ArrayList<>(raw.rows()));
			final SqlResult projected = SqlInformationSchemaExecutor.project(
					SqlResult.resultSet(raw.columns(), rows), s);
			rows = SqlProjectionOps.applyDistinct(s, new ArrayList<>(projected.rows()));
			return SqlResult.resultSet(
					projected.columns(),
					SqlResultSortOps.applyOrderLimit(s, projected.columns(), rows));
		}
		if (s.hasWindow()) {
			return selectWindow(session, s);
		}
		if (s.aggregate()) {
			return selectAggregate(session, s);
		}

		if (s.hasJoins()) {
			return selectJoin(session, s);
		}

		if (SqlProjectionOps.hasFunctionProjection(s)) {
			return selectWithUdfProjection(session, s);
		}

		final String table = tables.resolveTable(session, s.table());
		final TableStore store = tables.requireStore(table);
		final LockBatch lockBatch = acquireForUpdate(session, table, store, s);
		final List<byte[]> statementLocks = lockBatch.statementLocks();
		final List<String> projection = s.projection();
		final List<SqlResult.ColumnMeta> metas = SqlProjectionOps.columnMetas(store, projection);
		try {
			final SqlResult raw;
			if (s.forUpdate()) {
				// Local + optional peer locks; PREPARE/COMMIT votes via DistForUpdatePrepareVotes.
				raw = selectLockedKeys(session, table, store, lockBatch.selectedKeys(), metas, projection);
			} else if (!session.inTransaction()) {
				raw = selectCommitted(store, s, metas, projection);
			} else {
				raw = selectInTx(session, store, table, s, metas, projection);
			}
			List<Object[]> rows = new ArrayList<>(raw.rows());
			rows = SqlProjectionOps.applyDistinct(s, rows);
			// Index / lock-cursor plan already applied ORDER BY + LIMIT/OFFSET — do not re-page
			// (re-applying offset would truncate LIMIT offset,count) or re-sort EQ+ORDER+LIMIT.
			return SqlResult.resultSet(metas, rows);
		} finally {
			for (byte[] key : statementLocks) {
				session.lockManager().unlock(table, key);
			}
			DistForUpdateCoordinator.releaseStatementPeerLocks(lockBatch.peerStatementLocks());
		}
	}

	/**
	 * Resolve and acquire row keys before reading. In a transaction locks are remembered until
	 * COMMIT/ROLLBACK; autocommit locks are returned for statement-scoped release.
	 * <p>
	 * When {@link SqlTableResolver#distForUpdatePeerLockAgents()} is configured, the same wire
	 * keys are locked on peers via {@link DistForUpdateCoordinator} (fail-closed). Empty agents
	 * keep local-only behavior. Peer PREPARE/COMMIT votes run via {@link DistForUpdatePrepareVotes}.
	 */
	private LockBatch acquireForUpdate(
			SqlSession session,
			String table,
			TableStore store,
			SelectSql s
	) {
		if (!s.forUpdate()) {
			return new LockBatch(List.of(), List.of(), List.of());
		}
		// Index / planner path only — never full map walk for lock acquisition.
		// Drop LIMIT/OFFSET here: residual WHERE must see all candidate keys; paging is below.
		final String lookupSql = SqlSelectSqlRender.renderForUpdateKeyLookup(s);
		if (!store.isSelectFullyIndexed(lookupSql)) {
			throw new IllegalArgumentException(
					"FOR UPDATE " + GridCompositeIndex.MSG_WHERE_REQUIRES_USABLE_INDEX);
		}
		final LockAwareKeyCursor.Batch batch = LockAwareKeyCursor.acquireFromSelect(
				session,
				table,
				store,
				lookupSql,
				s.skipLocked(),
				s.offset(),
				s.limitOrNull()
		);
		final List<byte[]> statementLocks = new ArrayList<>(batch.statementLocks());
		final List<DistForUpdatePeerLockAgent> agents = tables.distForUpdatePeerLockAgents();
		try {
			final DistForUpdateCoordinator.PeerLockBatch peerBatch = DistForUpdateCoordinator.acquirePeerLocks(
					session,
					table,
					batch.selectedKeys(),
					statementLocks,
					s.skipLocked(),
					agents,
					session.envelopeCoordinator()
			);
			return new LockBatch(statementLocks, peerBatch.selectedKeys(), peerBatch.peerLeases());
		} catch (RuntimeException ex) {
			for (byte[] key : statementLocks) {
				session.lockManager().unlock(table, key);
			}
			throw ex;
		}
	}

	private SqlResult selectLockedKeys(
			SqlSession session,
			String table,
			TableStore store,
			List<byte[]> keys,
			List<SqlResult.ColumnMeta> metas,
			List<String> projection
	) {
		final List<Object[]> rows = new ArrayList<>(keys.size());
		for (byte[] key : keys) {
			final SqlTxBuffer.DirtyEntry dirty = session.inTransaction()
					? session.requireTx().get(table, key)
					: null;
			final byte[] value = dirty == null ? store.getCommittedBytes(key) : dirty.valueBytesOrNull();
			if (value != null) {
				rows.add(store.projectBytes(value, projection));
			}
		}
		return SqlResult.resultSet(metas, rows);
	}

	/**
	 * Bare {@code SELECT fn(...)} — one synthetic row, no table scan.
	 */
	private SqlResult selectExprOnly(SelectSql s) {
		final List<SelectItem> items = s.selectItems();
		if (items == null || items.isEmpty()) {
			throw new IllegalArgumentException("expression SELECT requires select items");
		}
		final List<SqlResult.ColumnMeta> metas = SelectProjectionAssembler.metasExprOnly(items);
		final Object[] row = SelectProjectionAssembler.assembleExprOnly(items);
		return SqlResult.resultSet(metas, List.<Object[]>of(row));
	}


	private SqlResult selectFromTableUdf(SqlSession session, SelectSql s) {
		final FunctionFrom from = s.fromFunctionOrNull();
		final FunctionDef def = SqlUdfLookup.require(from.functionName());
		if (def.kind() != FunctionKind.TABLE || def.tableUdf() == null) {
			throw new IllegalArgumentException("not a table-valued function: " + from.functionName());
		}
		final Object[] args = SqlProjectionOps.resolveTvfArgs(from.args());
		final List<byte[]> blobs = def.tableUdf().apply(args);
		if (blobs == null) {
			throw new IllegalStateException("table UDF returned null: " + from.functionName());
		}
		final List<ColumnDef> cols = new ArrayList<>(def.tableColumnNames().size());
		for (int i = 0; i < def.tableColumnNames().size(); i++) {
			cols.add(new ColumnDef(
					def.tableColumnNames().get(i),
					def.tableColumnTypes().get(i),
					true,
					i,
					i == 0,
					false));
		}
		final TableSchema schema = new TableSchema(from.functionName(), cols, List.of(), 0L);
		final List<String> projection = s.projection();
		final boolean star = projection.size() == 1 && "*".equals(projection.getFirst());
		final List<SqlResult.ColumnMeta> metas;
		if (star) {
			metas = SqlProjectionOps.starMetas(cols, from.functionName(), SqlInformationSchemaExecutor.DEFAULT_SCHEMA);
		} else {
			metas = new ArrayList<>(projection.size());
			for (String name : projection) {
				boolean found = false;
				for (ColumnDef col : cols) {
					if (col.name().equalsIgnoreCase(name)) {
						metas.add(SqlResult.ColumnMeta.ofCatalog(
								col.name(), col.type(), col.nullable(), from.functionName(),
								SqlInformationSchemaExecutor.DEFAULT_SCHEMA));
						found = true;
						break;
					}
				}
				if (!found) {
					throw new IllegalArgumentException("unknown TVF column: " + name);
				}
			}
		}
		final FilterCondition filter = selectFilter(s.sql());
		final List<Object[]> rows = new ArrayList<>(blobs.size());
		for (byte[] blob : blobs) {
			if (blob == null || !filter.matches(blob, schema)) {
				continue;
			}
			final LogicalFieldCursor cursor = LogicalFieldCursor.open(schema, blob);
			final Object[] full = new Object[cols.size()];
			for (int i = 0; i < cols.size(); i++) {
				full[i] = cursor.read(i);
			}
			if (star) {
				rows.add(full);
			} else {
				final Object[] projected = new Object[projection.size()];
				for (int i = 0; i < projection.size(); i++) {
					projected[i] = full[SqlProjectionOps.indexOfColumn(cols, projection.get(i))];
				}
				rows.add(projected);
			}
		}
		final List<Object[]> out = SqlProjectionOps.applyDistinct(s, rows);
		return SqlResult.resultSet(metas, SqlResultSortOps.applyOrderLimit(s, metas, out));
	}

	private SqlResult selectWithUdfProjection(SqlSession session, SelectSql s) {
		final String table = tables.resolveTable(session, s.table());
		final TableStore store = tables.requireStore(table);
		final SelectSql starSelect = SqlSelectSqlRender.withStarProjection(s);
		final List<SqlResult.ColumnMeta> starMetas = SqlProjectionOps.columnMetas(store, List.of("*"));
		final SqlResult raw;
		if (!session.inTransaction()) {
			raw = selectCommitted(store, starSelect, starMetas, List.of("*"));
		} else {
			raw = selectInTx(session, store, table, starSelect, starMetas, List.of("*"));
		}
		final List<SelectItem> items = s.selectItems();
		final List<SqlResult.ColumnMeta> metas = SelectProjectionAssembler.metas(store, items, s.projection());
		final List<Object[]> projected = new ArrayList<>(raw.rows().size());
		for (Object[] full : raw.rows()) {
			projected.add(SelectProjectionAssembler.assembleFromRow(store, full, items, Map.of()));
		}
		List<Object[]> rows = SqlProjectionOps.applyDistinct(s, projected);
		return SqlResult.resultSet(metas, SqlResultSortOps.applyOrderLimit(s, metas, rows));
	}

	/**
	 * Evaluate WHERE scalar / IN / EXISTS subqueries into typed {@link FilterCondition}s.
	 * EXISTS uses LIMIT 1 early-stop probe (no IN-list Object materialization).
	 */
	private List<FilterCondition> evaluateWhereSubqueries(SqlSession session, SelectSql s) {
		if (!s.hasWhereSubqueries()) {
			return List.of();
		}
		final List<FilterCondition> out = new ArrayList<>(s.whereSubqueries().size());
		for (WhereSubquery wq : s.whereSubqueries()) {
			if (wq.existsProbe()) {
				final SelectSql probe = SqlSelectSqlRender.withOffsetLimit(wq.subquery(), 0, 1);
				final SqlResult sub = select(session, probe);
				out.add(sub.rows().isEmpty()
						? AlwaysFalseCondition.getInstance()
						: AlwaysTrueCondition.getInstance());
				continue;
			}
			final SqlResult sub = select(session, wq.subquery());
			if (wq.inList()) {
				out.add(new LogicalOperatorCondition(
						wq.column(),
						LogicalOperatorCondition.Operator.IN,
						SqlProjectionOps.inListValues(sub)));
			} else {
				out.add(new LogicalOperatorCondition(
						wq.column(),
						LogicalOperatorCondition.Operator.EQ,
						SqlProjectionOps.scalarSubqueryValue(sub)));
			}
		}
		return out;
	}

	private SqlResult selectInTx(
			SqlSession session,
			TableStore store,
			String table,
			SelectSql s,
			List<SqlResult.ColumnMeta> metas,
			List<String> projection
	) {
		final SqlTxBuffer tx = session.requireTx();
		if (s.pkColumnOrNull() != null
				&& store.schema().pkColumn().name().equalsIgnoreCase(s.pkColumnOrNull())) {
			final byte[] key = store.keyBytesForPk(s.pkValueOrNull());
			final SqlTxBuffer.DirtyEntry dirty = tx.get(table, key);
			if (dirty != null) {
				if (dirty.op() == SqlTxBuffer.Op.DELETE) {
					return SqlResult.resultSet(metas, List.of());
				}
				final Object[] row = store.projectBytes(dirty.valueBytesOrNull(), projection);
				return SqlResult.resultSet(metas, Collections.singletonList(row));
			}
			if (session.remoteDirtyEnabled()) {
				DistTxSnapshot.pin(System.nanoTime());
				try {
					if (SqlTxSnapshotOps.collectRemoteTombstones(table, tables.remoteDirtyPeerTombstoneKeyExecutors())
							.contains(new KeyWrapper(key))) {
						return SqlResult.resultSet(metas, List.of());
					}
				} finally {
					DistTxSnapshot.clear();
				}
			}

			return selectCommitted(store, s, metas, projection);
		}

		final List<Function<String, List<byte[]>>> committedPeers = tables.distributedPeerKeyExecutors();
		final List<Function<String, List<byte[]>>> dirtyPeers = session.remoteDirtyEnabled()
				? tables.remoteDirtyPeerKeyExecutors()
				: List.of();
		final List<BiFunction<String, byte[], byte[]>> peerBlobs = session.remoteDirtyEnabled()
				? SqlTxSnapshotOps.concat(tables.distributedPeerRowBlobFetchers(), tables.remoteDirtyPeerRowBlobFetchers())
				: tables.distributedPeerRowBlobFetchers();
		final boolean usePeerBlobs = (committedPeers != null && !committedPeers.isEmpty())
				|| (peerBlobs != null && !peerBlobs.isEmpty());
		final boolean remoteDirtyOn = session.remoteDirtyEnabled();
		DistTxSnapshot.pin(System.nanoTime());
		try {
			final List<byte[]> keys;
			if (committedPeers != null && !committedPeers.isEmpty()) {
				final int limit = s.limitOrNull() == null ? 0 : Math.max(0, s.limitOrNull());
				final boolean pushLimit = s.limitOrNull() == null;
				keys = new ArrayList<>(DistributedKeyFanOut.fanOutKeys(
						s.sql(), limit, pushLimit, store, committedPeers));
			} else {
				keys = new ArrayList<>(store.selectKeys(s.sql(), subqueryFiltersTls.get()));
			}
			// Remote dirty upsert keys: always merge explicitly (not legacy identical fan-out).
			SqlTxSnapshotOps.mergeRemoteDirtyKeys(keys, dirtyPeers, s.sql());
			final Set<KeyWrapper> remoteTombstones = remoteDirtyOn
					? SqlTxSnapshotOps.collectRemoteTombstones(table, tables.remoteDirtyPeerTombstoneKeyExecutors())
					: Set.of();

			final List<Object[]> rows = new ArrayList<>();
			final Set<KeyWrapper> seen = new LinkedHashSet<>();
			for (byte[] key : keys) {
				final KeyWrapper kw = new KeyWrapper(key);
				seen.add(kw);

				final SqlTxBuffer.DirtyEntry dirty = tx.get(table, key);
				if (dirty != null) {
					if (dirty.op() == SqlTxBuffer.Op.DELETE) {
						continue;
					}

					rows.add(store.projectBytes(dirty.valueBytesOrNull(), projection));
				} else if (remoteTombstones.contains(kw)) {
					continue;
				} else {
					final byte[] value = usePeerBlobs
							? SqlTxSnapshotOps.resolveBlobUnderSnapshot(table, store, key, peerBlobs)
							: store.getCommittedBytes(key);
					if (value != null) {
						rows.add(store.projectBytes(value, projection));
					}
				}
			}

			final FilterCondition dirtyFilter = selectFilter(s.sql());
			for (Map.Entry<KeyWrapper, SqlTxBuffer.DirtyEntry> e : tx.entriesForTable(table).entrySet()) {
				if (seen.contains(e.getKey()) || e.getValue().op() != SqlTxBuffer.Op.UPSERT) {
					continue;
				}

				final byte[] valueBytes = e.getValue().valueBytesOrNull();
				if (valueBytes == null) {
					continue;
				}

				if (!dirtyFilter.matches(valueBytes, store.schema())) {
					continue;
				}

				rows.add(store.projectBytes(valueBytes, projection));
			}

			return SqlResult.resultSet(metas, rows);
		} finally {
			DistTxSnapshot.clear();
		}
	}

	/**
	 * Snapshot-read: dirty TX overlay union committed value blobs (wire residual via
	 * {@link FilterCondition#matches(byte[], TableSchema)}; no Object decode).
	 */
	private List<byte[]> snapshotBlobs(
			SqlSession session,
			TableStore store,
			String table,
			FilterCondition filter
	) {
		final TableSchema schema = store.schema();
		final List<byte[]> blobs = new ArrayList<>();
		if (!session.inTransaction()) {
			SqlTxSnapshotOps.forEachSnapshotCommitted(store, schema, filter, blobs);
			return blobs;
		}

		final SqlTxBuffer tx = session.requireTx();
		final Set<KeyWrapper> seen = new LinkedHashSet<>();
		SqlTxSnapshotOps.forEachSnapshotKey(store, filter, key -> {
			final byte[] value = store.getCommittedBytes(key);
			if (value == null) {
				return;
			}
			final KeyWrapper kw = new KeyWrapper(key);
			seen.add(kw);
			final SqlTxBuffer.DirtyEntry dirty = tx.get(table, key);
			if (dirty != null) {
				if (dirty.op() == SqlTxBuffer.Op.DELETE) {
					return;
				}
				final byte[] dirtyBytes = dirty.valueBytesOrNull();
				if (dirtyBytes != null && filter.matches(dirtyBytes, schema)) {
					blobs.add(dirtyBytes);
				}
			} else if (filter.matches(value, schema)) {
				blobs.add(value);
			}
		});
		for (Map.Entry<KeyWrapper, SqlTxBuffer.DirtyEntry> e : tx.entriesForTable(table).entrySet()) {
			if (seen.contains(e.getKey()) || e.getValue().op() != SqlTxBuffer.Op.UPSERT) {
				continue;
			}
			final byte[] valueBytes = e.getValue().valueBytesOrNull();
			if (valueBytes == null) {
				continue;
			}
			if (filter.matches(valueBytes, schema)) {
				blobs.add(valueBytes);
			}
		}
		return blobs;
	}

	/**
	 * Snapshot-read with Object decode (non-JOIN SELECT / window / aggregate path).
	 */
	private List<Object[]> snapshotRows(
			SqlSession session,
			TableStore store,
			String table,
			FilterCondition filter
	) {
		final List<byte[]> blobs = snapshotBlobs(session, store, table, filter);
		final List<Object[]> rows = new ArrayList<>(blobs.size());
		for (byte[] value : blobs) {
			rows.add(store.projectBytes(value, List.of("*")));
		}
		return rows;
	}

	public SqlResult explain(SqlSession session, SelectSql s) {
		return explainService.explain(session, s);
	}

	public SqlResult explainAnalyze(SqlSession session, SelectSql s) {
		return explainService.explainAnalyze(session, s);
	}

	public SqlResult selectAggregate(SqlSession session, SelectSql s) {
		if (SqlWireAggOps.isCountStarGroupBy(s)) {
			final String table = tables.resolveTable(session, s.table());
			final TableStore store = tables.requireStore(table);
			final FilterCondition filter = selectFilter(s.sql());
			final List<byte[]> blobs = snapshotBlobs(session, store, table, filter);
			final List<String> groupCols = s.groupByColumns();
			final int[] gOrds = new int[groupCols.size()];
			final List<ColumnDef> gDefs = new ArrayList<>(groupCols.size());
			for (int i = 0; i < groupCols.size(); i++) {
				gOrds[i] = SqlProjectionOps.ordinalOf(store.schema().columns(), groupCols.get(i));
				gDefs.add(store.schema().columns().get(gOrds[i]));
			}
			final String aggLabel = SqlProjectionOps.firstAggregateLabel(s);
			List<Object[]> out = SqlWireAggOps.countStarGroupByBlobs(store.schema(), blobs, gOrds);
			final List<SqlResult.ColumnMeta> metas = SqlWireAggOps.countGroupMetas(groupCols, gDefs, aggLabel);
			if (s.havingOrNull() != null) {
				out = SqlResultSortOps.applyHaving(s.havingOrNull(), metas, out);
			}
			return SqlResult.resultSet(metas, SqlResultSortOps.applyOrderLimit(s, metas, out));
		}
		if (SqlWireAggOps.isMinMaxNoGroup(s)) {
			final String table = tables.resolveTable(session, s.table());
			final TableStore store = tables.requireStore(table);
			final FilterCondition filter = selectFilter(s.sql());
			final List<byte[]> blobs = snapshotBlobs(session, store, table, filter);
			final int colOrd = SqlProjectionOps.ordinalOf(store.schema().columns(), s.sumColumnOrNull());
			final double v = SqlWireAggOps.minMaxFromBlobs(store.schema(), blobs, colOrd, s.minAgg());
			final String aggLabel = SqlProjectionOps.firstAggregateLabel(s);
			final List<SqlResult.ColumnMeta> metas = List.of(SqlResult.ColumnMeta.of(aggLabel, SqlType.DOUBLE));
			final List<Object[]> single = new ArrayList<>(1);
			single.add(new Object[]{v});
			return SqlResult.resultSet(metas, single);
		}

		final List<ColumnDef> cols;
		final List<Object[]> source;
		if (s.hasJoins()) {
			final JoinedWorking joined = materializeJoinWorking(session, s);
			cols = joined.cols();
			final List<SqlResult.ColumnMeta> fullMetas = SqlProjectionOps.starMetas(cols);
			source = applyWhere(s, fullMetas, SqlJoinOps.decodeJoinedRows(joined.rows(), joined.sideSchemas()));
		} else {
			final String table = tables.resolveTable(session, s.table());
			final TableStore store = tables.requireStore(table);
			cols = store.schema().columns();
			final FilterCondition filter = selectFilter(s.sql());
			source = snapshotRows(session, store, table, filter);
		}

		final List<String> groupCols = s.groupByColumns();
		final List<SqlResult.ColumnMeta> metas;
		List<Object[]> out;
		final String aggLabel = SqlProjectionOps.firstAggregateLabel(s);
		if (groupCols == null || groupCols.isEmpty()) {
			metas = List.of(SqlResult.ColumnMeta.of(aggLabel, SqlType.DOUBLE));

			final List<Object[]> single = new ArrayList<>(1);
			single.add(new Object[]{SqlProjectionOps.aggregateValue(cols, source, s)});

			out = single;
		} else {
			final int[] gOrds = new int[groupCols.size()];
			final List<ColumnDef> gDefs = new ArrayList<>(groupCols.size());
			for (int i = 0; i < groupCols.size(); i++) {
				gOrds[i] = SqlProjectionOps.ordinalOf(cols, groupCols.get(i));
				gDefs.add(cols.get(gOrds[i]));
			}
			final Map<WireFieldBytes, List<Object[]>> groups = new LinkedHashMap<>();
			final Map<WireFieldBytes, Object[]> emitKeys = new LinkedHashMap<>();
			for (Object[] row : source) {
				final WireFieldBytes key = SqlWireAggOps.compositeWireKey(row, gOrds);
				groups.computeIfAbsent(key, _ -> new ArrayList<>(4)).add(row);
				emitKeys.computeIfAbsent(key, _ -> {
					final Object[] emit = new Object[gOrds.length];
					for (int i = 0; i < gOrds.length; i++) {
						emit[i] = row[gOrds[i]];
					}
					return emit;
				});
			}

			metas = SqlWireAggOps.countGroupMetas(groupCols, gDefs, aggLabel);

			out = new ArrayList<>(groups.size());
			for (Map.Entry<WireFieldBytes, List<Object[]>> e : groups.entrySet()) {
				final Object[] emit = emitKeys.get(e.getKey());
				final Object[] row = new Object[emit.length + 1];
				System.arraycopy(emit, 0, row, 0, emit.length);
				row[emit.length] = SqlProjectionOps.aggregateValue(cols, e.getValue(), s);
				out.add(row);
			}
		}

		if (s.havingOrNull() != null) {
			out = SqlResultSortOps.applyHaving(s.havingOrNull(), metas, out);
		}

		return SqlResult.resultSet(metas, SqlResultSortOps.applyOrderLimit(s, metas, out));
	}

	private SqlResult selectWindow(SqlSession session, SelectSql s) {
		final List<ColumnDef> cols;
		final List<Object[]> source;
		if (s.hasJoins()) {
			final JoinedWorking joined = materializeJoinWorking(session, s);
			cols = joined.cols();
			source = applyWhere(s, SqlProjectionOps.starMetas(cols), SqlJoinOps.decodeJoinedRows(joined.rows(), joined.sideSchemas()));
		} else {
			final String table = tables.resolveTable(session, s.table());
			final TableStore store = tables.requireStore(table);
			cols = store.schema().columns();
			final FilterCondition filter = selectFilter(s.sql());
			source = snapshotRows(session, store, table, filter);
		}

		final List<SelectItem> items = s.selectItems() == null ? List.of() : s.selectItems();
		final List<WindowSelectItem> windows = new ArrayList<>(2);
		for (SelectItem item : items) {
			if (item instanceof WindowSelectItem w) {
				windows.add(w);
			}
		}
		if (windows.isEmpty() && s.windowFuncOrNull() != null) {
			windows.add(new WindowSelectItem(
					s.projection().isEmpty() ? s.windowFuncOrNull() : s.projection().getFirst(),
					s.windowFuncOrNull(),
					s.sumColumnOrNull(),
					s.windowPartitionColumns(),
					s.windowOrderColOrNull()
			));
		}

		List<Object[]> working = source;
		final Map<String, Map<WireFieldBytes, Object>> windowByRow = new HashMap<>();
		for (WindowSelectItem win : windows) {
			final WindowOperator.Spec spec = new WindowOperator.Spec(
					win.func(),
					win.partitionColumns(),
					win.orderColOrNull(),
					win.valueColumnOrNull()
			);
			final List<Object[]> numbered = WindowOperator.apply(cols, working, spec);
			final Map<WireFieldBytes, Object> byRow = new HashMap<>(Math.max(HASH_MAP_MIN_CAPACITY, numbered.size() * 2));
			for (Object[] row : numbered) {
				final Object[] full = Arrays.copyOf(row, row.length - 1);
				byRow.put(SqlWireAggOps.wireKeyOfRow(full), row[row.length - 1]);
			}
			windowByRow.put(win.label(), byRow);
			working = source;
		}

		final List<SqlResult.ColumnMeta> metas;
		final List<Object[]> projected = new ArrayList<>(source.size());
		if (items.isEmpty() || (items.size() == 1 && items.getFirst() instanceof WindowSelectItem)) {
			final WindowSelectItem only = windows.getFirst();
			metas = List.of(SqlResult.ColumnMeta.of(only.label(), SqlType.DOUBLE));
			final Map<WireFieldBytes, Object> byRow = windowByRow.get(only.label());
			for (Object[] row : source) {
				projected.add(new Object[]{byRow.get(SqlWireAggOps.wireKeyOfRow(row))});
			}
		} else if (s.hasJoins()) {
			metas = SelectProjectionAssembler.metasFromColumns(cols, items, s.projection());
			for (Object[] row : source) {
				final Map<String, Object> computed = new HashMap<>(windows.size() * 2);
				final WireFieldBytes key = SqlWireAggOps.wireKeyOfRow(row);
				for (WindowSelectItem win : windows) {
					computed.put(win.label(), windowByRow.get(win.label()).get(key));
				}
				projected.add(SelectProjectionAssembler.assembleFromRowColumns(cols, row, items, computed));
			}
		} else {
			final TableStore store = tables.requireStore(tables.resolveTable(session, s.table()));
			metas = SelectProjectionAssembler.metas(store, items, s.projection());
			for (Object[] row : source) {
				final Map<String, Object> computed = new HashMap<>(windows.size() * 2);
				final WireFieldBytes key = SqlWireAggOps.wireKeyOfRow(row);
				for (WindowSelectItem win : windows) {
					computed.put(win.label(), windowByRow.get(win.label()).get(key));
				}
				projected.add(SelectProjectionAssembler.assembleFromRow(store, row, items, computed));
			}
		}
		return SqlResult.resultSet(metas, SqlResultSortOps.applyOrderLimit(s, metas, projected));
	}

	/**
	 * INNER / LEFT / RIGHT / FULL OUTER JOIN chain with optional WHERE / ORDER BY / LIMIT.
	 * {@code FOR UPDATE} is supported for INNER JOIN chains (locks every side's PK map keys).
	 */
	public SqlResult selectJoin(SqlSession session, SelectSql s) {
		final JoinedWorking joined = materializeJoinWorking(session, s);
		List<SqlJoinOps.JoinBlobRow> blobRows = joined.rows();
		final int earlyLimit = SqlSelectOrderOps.earlyLimitOrZero(s);
		if (earlyLimit > 0 && blobRows.size() > earlyLimit) {
			blobRows = blobRows.subList(0, earlyLimit);
		}

		final List<ColumnDef> workingCols = joined.cols();
		final List<String> rawSides = SqlJoinOps.joinSideTables(s);
		final List<String> resolvedSides = new ArrayList<>(rawSides.size());
		for (String raw : rawSides) {
			resolvedSides.add(domains.resolve(session, raw));
		}
		SqlForUpdateJoinLockOps.MultiTableLockBatch joinLocks = null;
		if (s.forUpdate()) {
			joinLocks = SqlForUpdateJoinLockOps.acquireJoinLocks(
					session,
					resolvedSides,
					joined.sideSchemas(),
					blobRows,
					tables.distForUpdatePeerLockAgents());
		}
		try {
			final List<Object[]> decoded = SqlJoinOps.decodeJoinedRows(blobRows, joined.sideSchemas());

			final List<String> projection = s.projection();
			final boolean star = projection.size() == 1 && "*".equals(projection.getFirst());
			final List<SqlResult.ColumnMeta> metas = SqlProjectionOps.joinMetas(workingCols, projection, star, s.selectItems());

			List<Object[]> projected = new ArrayList<>(decoded.size());
			for (Object[] row : decoded) {
				projected.add(SqlJoinOps.projectJoined(workingCols, row, projection, star, s.selectItems(), rawSides));
			}

			if (!joined.whereAppliedOnWire()) {
				projected = applyWhere(s, metas, projected);
			}
			projected = SqlProjectionOps.applyDistinct(s, projected);
			return SqlResult.resultSet(metas, SqlResultSortOps.applyOrderLimit(s, metas, projected));
		} finally {
			SqlForUpdateJoinLockOps.release(session, joinLocks);
		}
	}

	/**
	 * Full-width join working set (all side columns) before projection / WHERE.
	 * <p>
	 * Intermediate rows are {@link JoinBlobRow} wire blobs; {@code Object[]} decode
	 * happens only at the SqlResult / project edge.
	 * <p>
	 * With {@code grid.sql.distributed-peers}: PK/probe side stays local (dirty overlay via
	 * {@link #snapshotBlobs}); build side fan-in via {@link DistributedKeyFanOut#fanInBuildBlobs}
	 * (committed peers only — TX barrier), then reuse JOIN_PK / JOIN_HASH kernels.
	 * Fan-in is allowed while {@link SqlSession#inTransaction()} for read-only committed fan-out.
	 */
	JoinedWorking materializeJoinWorking(SqlSession session, SelectSql s) {
		final List<JoinEdge> edges = s.joins();
		final String leftName = domains.resolve(session, s.table());
		final List<Function<String, List<byte[]>>> peers = tables.distributedPeerKeyExecutors();
		final List<BiFunction<String, byte[], byte[]>> peerBlobs = tables.distributedPeerRowBlobFetchers();
		final boolean distFanIn = peers != null && !peers.isEmpty();

		TableStore left = domains.requireStore(leftName);
		List<SqlJoinOps.JoinBlobRow> working = null;
		List<ColumnDef> workingCols = new ArrayList<>(left.schema().columns());
		List<TableSchema> sideSchemas = new ArrayList<>();
		sideSchemas.add(left.schema());
		FilterCondition leftPushFilter = leftOnlyJoinFilter(s, left.schema());
		boolean whereAppliedOnWire = !(leftPushFilter instanceof AlwaysTrueCondition);
		final int earlyLimit = SqlSelectOrderOps.earlyLimitOrZero(s);
		final FilterCondition joinWhereFilter = selectFilter(s.sql());

		for (JoinEdge edge : edges) {
			final String rightName = domains.resolve(session, edge.table());
			final TableStore right = domains.requireStore(rightName);
			final ColumnDef leftCol = SqlProjectionOps.requireWorkingColumn(workingCols, edge.leftCol());
			final ColumnDef rightCol = right.schema().requireColumn(edge.rightCol());
			final int leftOrd = SqlProjectionOps.indexOfColumn(workingCols, leftCol.name());
			final int rightOrd = rightCol.ordinal();
			final boolean leftOnPk = leftCol.primaryKey();
			final boolean rightOnPk = rightCol.primaryKey();

			if (distFanIn) {
				if (session.inTransaction()) {
					DistTxSnapshot.pin(System.nanoTime());
					try {
						working = joinEdgeDistributed(
								session, working, sideSchemas, left, leftName, right, rightName,
								leftOrd, rightOrd, leftOnPk, rightOnPk, edge, peers, peerBlobs);
					} finally {
						DistTxSnapshot.clear();
					}
				} else {
					working = joinEdgeDistributed(
							session, working, sideSchemas, left, leftName, right, rightName,
							leftOrd, rightOrd, leftOnPk, rightOnPk, edge, peers, peerBlobs);
				}
				workingCols = SqlJoinOps.concatColumns(workingCols, right.schema().columns());
				sideSchemas = new ArrayList<>(sideSchemas);
				sideSchemas.add(right.schema());
				left = right;
				continue;
			}

			final boolean rightIndexed = right.hasColumnIndex(rightCol.name());
			if (!whereAppliedOnWire && working == null) {
				final FilterCondition remapped = SqlJoinFilterPush.remapJoinColToLeft(
						joinWhereFilter, edge.leftCol(), edge.rightCol(), left.schema());
				if (!(remapped instanceof AlwaysTrueCondition)) {
					leftPushFilter = remapped;
					whereAppliedOnWire = true;
				}
			}
			// Prefer selective left filter then right PK/index probe (avoids full right searchAll).
			if (working == null
					&& leftOnPk
					&& edge.kind() == JoinKind.INNER
					&& whereAppliedOnWire
					&& (rightOnPk || rightIndexed)) {
				working = SqlJoinOps.toJoinRows(snapshotBlobs(session, left, leftName, leftPushFilter));
				if (rightOnPk) {
					working = SqlJoinOps.joinProbeRightPkStore(
							working, sideSchemas, right, leftOrd, false, earlyLimit);
				} else {
					working = SqlJoinOps.joinProbeRightIndexStore(
							working, sideSchemas, right, leftOrd, rightCol.name());
				}
				workingCols = SqlJoinOps.concatColumns(left.schema().columns(), right.schema().columns());
				sideSchemas = List.of(left.schema(), right.schema());
				left = right;
				continue;
			}
			if (working == null && leftOnPk && edge.kind() == JoinKind.INNER) {
				final List<byte[]> rightBlobs = snapshotBlobs(
						session, right, rightName, AlwaysTrueCondition.getInstance());
				final FilterCondition leftResidual =
						leftPushFilter instanceof AlwaysTrueCondition ? null : leftPushFilter;
				working = SqlJoinOps.joinProbeLeftPk(
						rightBlobs, left, right.schema(), rightOrd, leftResidual, earlyLimit);
				whereAppliedOnWire = leftResidual != null;
				workingCols = SqlJoinOps.concatColumns(left.schema().columns(), right.schema().columns());
				sideSchemas = List.of(left.schema(), right.schema());
				left = right;
				continue;
			}
			if (working == null) {
				working = SqlJoinOps.toJoinRows(snapshotBlobs(session, left, leftName, leftPushFilter));
			}
			if (rightOnPk && edge.kind() != JoinKind.RIGHT && edge.kind() != JoinKind.FULL) {
				working = SqlJoinOps.joinProbeRightPkStore(
						working, sideSchemas, right, leftOrd,
						edge.kind() == JoinKind.LEFT || edge.kind() == JoinKind.FULL,
						earlyLimit);
			} else if (rightIndexed && edge.kind() == JoinKind.INNER) {
				working = SqlJoinOps.joinProbeRightIndexStore(
						working, sideSchemas, right, leftOrd, rightCol.name());
			} else {
				final List<byte[]> rightBlobs = snapshotBlobs(
						session, right, rightName, AlwaysTrueCondition.getInstance());
				working = SqlJoinOps.joinStep(
						working, sideSchemas, right, rightBlobs, leftOrd, rightOrd, edge.kind());
			}
			workingCols = SqlJoinOps.concatColumns(workingCols, right.schema().columns());
			sideSchemas = new ArrayList<>(sideSchemas);
			sideSchemas.add(right.schema());
			left = right;
		}
		if (working == null) {
			working = List.of();
		}
		return new JoinedWorking(workingCols, sideSchemas, working, whereAppliedOnWire);
	}

	/**
	 * Distributed JOIN edge: PK/probe store local; build side key fan-in + remote blob fetch;
	 * reuse join kernels.
	 */
	private List<SqlJoinOps.JoinBlobRow> joinEdgeDistributed(
			SqlSession session,
			List<SqlJoinOps.JoinBlobRow> working,
			List<TableSchema> sideSchemas,
			TableStore left,
			String leftName,
			TableStore right,
			String rightName,
			int leftOrd,
			int rightOrd,
			boolean leftOnPk,
			boolean rightOnPk,
			JoinEdge edge,
			List<Function<String, List<byte[]>>> peers,
			List<BiFunction<String, byte[], byte[]>> peerBlobs
	) {
		// PK probe right: left/working stays local; fan-in right build blobs (incl. peer fetch).
		if (rightOnPk && edge.kind() != JoinKind.RIGHT && edge.kind() != JoinKind.FULL) {
			final List<SqlJoinOps.JoinBlobRow> probeRows = working != null
					? working
					: SqlJoinOps.toJoinRows(snapshotBlobs(session, left, leftName, AlwaysTrueCondition.getInstance()));
			final List<byte[]> rightBlobs = DistributedKeyFanOut.fanInBuildBlobs(
					rightName, right, peers, peerBlobs);
			return SqlJoinOps.joinStep(
					probeRows, sideSchemas, right, rightBlobs, leftOrd, rightOrd, edge.kind());
		}
		// PK probe left local: fan-in right (build of probe input).
		if (working == null && leftOnPk && edge.kind() == JoinKind.INNER) {
			final List<byte[]> rightBlobs = DistributedKeyFanOut.fanInBuildBlobs(
					rightName, right, peers, peerBlobs);
			return SqlJoinOps.joinProbeLeftPk(rightBlobs, left, right.schema(), rightOrd);
		}
		// JOIN_HASH: probe side local snapshot; build side fan-in.
		final List<SqlJoinOps.JoinBlobRow> leftRows = working != null
				? working
				: SqlJoinOps.toJoinRows(snapshotBlobs(session, left, leftName, AlwaysTrueCondition.getInstance()));
		final List<byte[]> rightBlobs = DistributedKeyFanOut.fanInBuildBlobs(
				rightName, right, peers, peerBlobs);
		return SqlJoinOps.joinStep(
				leftRows, sideSchemas, right, rightBlobs, leftOrd, rightOrd, edge.kind());
	}

	/**
	 * Column defs for JOIN MV schema (left + each join side, no data).
	 */
	public List<ColumnDef> joinSchemaColumns(SqlSession session, SelectSql s) {
		final String leftName = domains.resolve(session, s.table());
		List<ColumnDef> cols = new ArrayList<>(domains.requireStore(leftName).schema().columns());
		for (JoinEdge edge : s.joins()) {
			final String rightName = domains.resolve(session, edge.table());
			cols = SqlJoinOps.concatColumns(cols, domains.requireStore(rightName).schema().columns());
		}
		return cols;
	}

	record JoinedWorking(
			List<ColumnDef> cols,
			List<TableSchema> sideSchemas,
			List<SqlJoinOps.JoinBlobRow> rows,
			boolean whereAppliedOnWire
	) {
		JoinedWorking(List<ColumnDef> cols, List<TableSchema> sideSchemas, List<SqlJoinOps.JoinBlobRow> rows) {
			this(cols, sideSchemas, rows, false);
		}
	}


	private FilterCondition leftOnlyJoinFilter(SelectSql s, TableSchema leftSchema) {
		final FilterCondition filter = selectFilter(s.sql());
		if (filter instanceof AlwaysTrueCondition) {
			return filter;
		}
		try {
			filter.validate(leftSchema);
			return filter;
		} catch (RuntimeException ignored) {
			return AlwaysTrueCondition.getInstance();
		}
	}

	private List<Object[]> applyWhere(SelectSql s, List<SqlResult.ColumnMeta> metas, List<Object[]> rows) {
		final FilterCondition filter = selectFilter(s.sql());
		if (filter instanceof AlwaysTrueCondition) {
			return rows;
		}

		final List<Object[]> out = new ArrayList<>(rows.size());
		for (Object[] row : rows) {
			if (filter.matchesColumns(name -> SqlProjectionOps.columnValue(metas, row, name))) {
				out.add(row);
			}
		}
		return out;
	}

	private FilterCondition selectFilter(String selectSql) {
		final List<FilterCondition> resolved = subqueryFiltersTls.get();
		final QueryData qd = QueryParser.parseAndBuildCondition(
				null, selectSql, Collections.emptyMap(), resolved);
		if (qd.filter() == null || qd.filter().conditionTree() == null) {
			return AlwaysTrueCondition.getInstance();
		}

		return qd.filter().conditionTree();
	}

	private SqlResult selectCommitted(
			TableStore store,
			SelectSql s,
			List<SqlResult.ColumnMeta> metas,
			List<String> projection
	) {
		final List<Object[]> rows;
		if (s.pkColumnOrNull() != null
				&& store.schema().pkColumn().name().equalsIgnoreCase(s.pkColumnOrNull())) {
			final Object[] full = store.getByPk(s.pkValueOrNull());
			if (full == null) {
				rows = List.of();
			} else if (projection.size() == 1 && "*".equals(projection.getFirst())) {
				rows = Collections.singletonList(full);
			} else {
				final Object[] projected = new Object[projection.size()];
				for (int i = 0; i < projection.size(); i++) {
					projected[i] = full[store.schema().requireColumn(projection.get(i)).ordinal()];
				}

				rows = Collections.singletonList(projected);
			}
		} else {
			final List<Function<String, List<byte[]>>> peers = tables.distributedPeerKeyExecutors();
			if (peers != null && !peers.isEmpty()) {
				final int limit = s.limitOrNull() == null ? 0 : Math.max(0, s.limitOrNull());
				final boolean pushLimit = s.limitOrNull() == null;
				rows = DistributedKeyFanOut.keysThenProject(
						s.sql(),
						limit,
						pushLimit,
						store,
						projection,
						peers,
						tables.distributedPeerRowBlobFetchers());
			} else {
				final List<byte[]> keys = store.selectKeys(s.sql(), subqueryFiltersTls.get());
				rows = new ArrayList<>(keys.size());
				for (byte[] key : keys) {
					final byte[] value = store.getCommittedBytes(key);
					if (value != null) {
						rows.add(store.projectBytes(value, projection));
					}
				}
			}
		}

		return SqlResult.resultSet(metas, rows);
	}
}
