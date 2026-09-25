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
package org.genfork.grid.sql;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.google.common.annotations.VisibleForTesting;

import org.genfork.grid.catalog.FunctionKind;
import org.genfork.grid.catalog.PrivilegeCatalog;
import org.genfork.grid.catalog.SqlPrivilege;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.catalog.TableCatalog.FunctionDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.common.GridProcessFence;
import org.genfork.grid.metrics.SqlTxMetrics;
import org.genfork.grid.overlay.OverlayStore;
import org.genfork.grid.replication.ReplicaAccessGate;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.ast.DdlAst.AlterTableSql;
import org.genfork.grid.sql.ast.DmlAst.AnalyzeSql;
import org.genfork.grid.sql.ast.TxAst.BeginSql;
import org.genfork.grid.sql.ast.TxAst.CommitSql;
import org.genfork.grid.sql.ast.DdlAst.CreateFunctionSql;
import org.genfork.grid.sql.ast.DdlAst.CreateTriggerSql;
import org.genfork.grid.sql.ast.DdlAst.CreateIndexSql;
import org.genfork.grid.sql.ast.DdlAst.CreateMaterializedViewSql;
import org.genfork.grid.sql.ast.DdlAst.CreateSchemaSql;
import org.genfork.grid.sql.ast.DdlAst.CreateSequenceSql;
import org.genfork.grid.sql.ast.DdlAst.CreateTableSql;
import org.genfork.grid.sql.ast.AdminAst.AlterUserPasswordSql;
import org.genfork.grid.sql.ast.AdminAst.CreateRoleSql;
import org.genfork.grid.sql.ast.AdminAst.CreateUserSql;
import org.genfork.grid.sql.ast.DdlAst.CreateViewSql;
import org.genfork.grid.sql.ast.TxAst.DeallocateSql;
import org.genfork.grid.sql.ast.DmlAst.DeleteSql;
import org.genfork.grid.sql.ast.DmlAst.TruncateSql;
import org.genfork.grid.sql.ast.DdlAst.DropFunctionSql;
import org.genfork.grid.sql.ast.DdlAst.DropTriggerSql;
import org.genfork.grid.sql.ast.DdlAst.DropIndexSql;
import org.genfork.grid.sql.ast.DdlAst.DropSchemaSql;
import org.genfork.grid.sql.ast.DdlAst.DropSequenceSql;
import org.genfork.grid.sql.ast.DdlAst.DropTableSql;
import org.genfork.grid.sql.ast.AdminAst.DropRoleSql;
import org.genfork.grid.sql.ast.AdminAst.DropUserSql;
import org.genfork.grid.sql.ast.DdlAst.DropViewSql;
import org.genfork.grid.sql.ast.TxAst.ExecuteSql;
import org.genfork.grid.sql.ast.SelectAst.ExplainSql;
import org.genfork.grid.sql.ast.SelectAst.JoinEdge;
import org.genfork.grid.sql.ast.AdminAst.GrantRoleMembershipSql;
import org.genfork.grid.sql.ast.AdminAst.GrantSql;
import org.genfork.grid.sql.ast.AdminAst.GrantToRoleSql;
import org.genfork.grid.sql.ast.DmlAst.InsertSql;
import org.genfork.grid.sql.ast.DmlAst.MergeSql;
import org.genfork.grid.sql.ast.AdminAst.PinSql;
import org.genfork.grid.sql.ast.TxAst.PrepareSql;
import org.genfork.grid.sql.ast.TxAst.ReleaseSavepointSql;
import org.genfork.grid.sql.ast.SelectAst.RecursiveCteSql;
import org.genfork.grid.sql.ast.DdlAst.RefreshMaterializedViewSql;
import org.genfork.grid.sql.ast.TxAst.RollbackSql;
import org.genfork.grid.sql.ast.TxAst.RollbackToSavepointSql;
import org.genfork.grid.sql.ast.TxAst.SavepointSql;
import org.genfork.grid.sql.ast.AdminAst.RevokeSql;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.genfork.grid.sql.ast.DdlAst.SequenceValueSql;
import org.genfork.grid.sql.ast.DdlAst.SetSchemaSql;
import org.genfork.grid.sql.ast.DdlAst.SetRemoteDirtySql;
import org.genfork.grid.sql.ast.Stmt;
import org.genfork.grid.sql.ast.SelectAst.SetOpSql;
import org.genfork.grid.sql.ast.AdminAst.UnpinSql;
import org.genfork.grid.sql.ast.DmlAst.UpdateSql;
import org.genfork.grid.sql.exec.RecursiveCteExecutor;
import org.genfork.grid.sql.exec.CrossDomainTableResolver;
import org.genfork.grid.sql.exec.SqlDdlExecutor;
import org.genfork.grid.sql.exec.SqlDmlExecutor;
import org.genfork.grid.sql.exec.SqlQueryExecutor;
import org.genfork.grid.sql.exec.SqlTableResolver;
import org.genfork.grid.sql.tx.DistForUpdatePeerLockAgent;
import org.genfork.grid.sql.tx.SqlRecordLockManager;
import org.genfork.grid.sql.tx.SqlAutocommit;
import org.genfork.grid.sql.tx.SqlTxCommitter;
import org.genfork.grid.sql.udf.SqlTableUdf;
import org.genfork.grid.sql.udf.SqlUdf;
import org.genfork.grid.sql.udf.SqlUdfBinder;
import org.genfork.grid.sql.udf.SqlUdfCallContext;
import org.genfork.grid.sql.udf.SqlUdfLookup;
import org.genfork.grid.sql.udf.SqlUdfMutatingOps;
import org.genfork.grid.store.TableStore;

/**
 * SQL facade: parse + dispatch to DDL / DML / query / TX / overlay pin subsystems.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlEngine {
	/** Default {@code WITH RECURSIVE} depth cap (matches {@code grid.sql.recursive-cte-max-depth}). */
	public static final int DEFAULT_RECURSIVE_CTE_MAX_DEPTH = 32;

	private final TableCatalog catalog;
	private final PrivilegeCatalog privileges;
	private final SqlTableResolver tables;
	private final SqlTxCommitter txCommitter;
	private final ReplicationCoordinator replication;
	private final SqlRecordLockManager lockManager = new SqlRecordLockManager();
	private final AtomicBoolean applyingReplicatedDdl = new AtomicBoolean(false);
	private final SqlDdlExecutor ddl;
	private final SqlDmlExecutor dml;
	private final SqlQueryExecutor query;
	private final int preparePoolSize;
	private int recursiveCteMaxDepth = DEFAULT_RECURSIVE_CTE_MAX_DEPTH;
	private ZoneId defaultTimezone = ZoneOffset.UTC;
	private OverlayStore overlayStore;

	public SqlEngine(
			TableCatalog catalog,
			ReplicationCoordinator replicationCoordinator,
			int defaultShards
	) {
		this(catalog, replicationCoordinator, defaultShards,
				SqlSession.DEFAULT_PREPARE_POOL_SIZE);
	}

	public SqlEngine(
			TableCatalog catalog,
			ReplicationCoordinator replicationCoordinator,
			int defaultShards,
			int preparePoolSize
	) {
		this.catalog = catalog;
		this.privileges = catalog.privileges();
		this.replication = replicationCoordinator;
		this.tables = new SqlTableResolver(
				catalog, replicationCoordinator, defaultShards);
		this.txCommitter = new SqlTxCommitter(catalog, replicationCoordinator);
		this.query = new SqlQueryExecutor(tables);
		this.ddl = new SqlDdlExecutor(tables, applyingReplicatedDdl, query);
		this.dml = new SqlDmlExecutor(tables, this);
		this.preparePoolSize = preparePoolSize <= 0
				? Integer.MAX_VALUE
				: preparePoolSize;
		if (replicationCoordinator != null && replicationCoordinator.isEnabled()) {
			replicationCoordinator.bindSqlCatalog(this::applyReplicatedDdl);
		}
	}

	/**
	 * Bind soft overlay store for {@code PIN}/{@code UNPIN} and optional auto-pin on hot write.
	 *
	 * @param overlayStore nullable; when null/disabled PIN rejects
	 * @param autoPinTtlMs {@code <= 0} disables auto-pin
	 */
	public void setOverlay(OverlayStore overlayStore, long autoPinTtlMs) {
		this.overlayStore = overlayStore;
		tables.setOverlay(overlayStore, autoPinTtlMs);
	}

	/**
	 * Peer key suppliers for distributed SELECT / JOIN fan-out
	 * ({@link org.genfork.grid.query.distributed.DistributedQueryExecutor} /
	 * {@link org.genfork.grid.query.distributed.DistributedKeyFanOut}).
	 * <p>
	 * Fan-out is allowed for read-only SELECT/JOIN while a TX is open; peers see
	 * committed state only (dirty stays local until COMMIT).
	 */
	public void setDistributedPeerKeyExecutors(List<Function<String, List<byte[]>>> peers) {
		tables.setDistributedPeerKeyExecutors(peers);
	}

	/**
	 * Peer row-blob fetchers for keys present only on remote peers (wire {@code byte[]}).
	 */
	public void setDistributedPeerRowBlobFetchers(List<BiFunction<String, byte[], byte[]>> fetchers) {
		tables.setDistributedPeerRowBlobFetchers(fetchers);
	}

	/**
	 * Test/internal wiring for opt-in remote dirty upsert fan-out.
	 * Production transports must provide ORCHID-admitted, transaction-scoped suppliers.
	 */
	@VisibleForTesting
	public void setRemoteDirtyPeerKeyExecutors(List<Function<String, List<byte[]>>> peers) {
		tables.setRemoteDirtyPeerKeyExecutors(peers);
	}

	@VisibleForTesting
	public void setRemoteDirtyPeerRowBlobFetchers(List<BiFunction<String, byte[], byte[]>> fetchers) {
		tables.setRemoteDirtyPeerRowBlobFetchers(fetchers);
	}

	@VisibleForTesting
	public void setRemoteDirtyPeerTombstoneKeyExecutors(List<Function<String, List<byte[]>>> peers) {
		tables.setRemoteDirtyPeerTombstoneKeyExecutors(peers);
	}

	/**
	 * Peer FOR UPDATE lock agents (Phase 3). Empty = local locks only.
	 * Prefer {@link org.genfork.grid.sql.tx.InProcessDistForUpdatePeerLockAgent} in tests.
	 */
	@VisibleForTesting
	public void setDistForUpdatePeerLockAgents(List<DistForUpdatePeerLockAgent> agents) {
		tables.setDistForUpdatePeerLockAgents(agents);
	}

	/**
	 * Register a cross-domain JOIN alias ({@code alias → qualified catalog table}).
	 */
	public void registerCrossDomainAlias(String alias, String qualifiedTable) {
		query.domains().registerAlias(alias, qualifiedTable);
	}

	/** Cross-domain JOIN alias registry. */
	public CrossDomainTableResolver crossDomain() {
		return query.domains();
	}

	/** Cap for {@code WITH RECURSIVE} iteration ({@code <= 0} rejected at execute). */
	public void setRecursiveCteMaxDepth(int maxDepth) {
		this.recursiveCteMaxDepth = maxDepth <= 0 ? DEFAULT_RECURSIVE_CTE_MAX_DEPTH : maxDepth;
	}

	/** Default session timezone ({@code grid.sql.timezone} / UTC). */
	public void setDefaultTimezone(ZoneId zone) {
		this.defaultTimezone = zone == null ? ZoneOffset.UTC : zone;
	}

	public ZoneId defaultTimezone() {
		return defaultTimezone;
	}

	public OverlayStore overlayStore() {
		return overlayStore;
	}

	public TableCatalog catalog() {
		return catalog;
	}

	public PrivilegeCatalog privileges() {
		return privileges;
	}


	/** LRU prepare pool cap applied to every new {@link SqlSession}. */
	public int preparePoolSize() {
		return preparePoolSize;
	}

	/** Shared fair record lock manager (SQL TX / DML). */
	public SqlRecordLockManager lockManager() {
		return lockManager;
	}

	public SqlSession newSession() {
		return newSession(localExecuteUser(), true);
	}

	public SqlSession newSession(String user, boolean authenticated) {
		final SqlSession session = new SqlSession(user, authenticated, lockManager, preparePoolSize);
		session.setTimezone(defaultTimezone);
		if (replication != null) {
			session.setEnvelopeCoordinator(replication.getTxEnvelopeCoordinator());
		}
		return session;
	}

	/**
	 * In-process {@link #execute(String)} identity: open catalog → anonymous; else bootstrap admin.
	 */
	private String localExecuteUser() {
		if (privileges == null || privileges.isOpen()) {
			return "anonymous";
		}
		final String admin = privileges.firstAdministrator();
		return admin != null ? admin : "anonymous";
	}

	public SqlResult execute(String sql) {
		return execute(newSession(), sql);
	}

	public SqlResult execute(SqlSession session, String sql) {
		return execute(session, sql, null);
	}

	public SqlResult execute(SqlSession session, String sql, Object[] binds) {
		GridProcessFence.checkAdmit("SqlEngine.execute");
		SqlTxMetrics.recordExecution();
		final SqlSession sess = session == null ? newSession() : session;
		final String effective = SqlBinds.materialize(sql, binds);
		final String expanded = SqlNamedQueryExpand.expand(effective, catalog.viewSelectBodies());
		final Stmt stmt = applyWireExecuteBinds(SqlStatementParser.parse(expanded, sess.timezone()), binds);
		SqlReplicaAdmission.afterParse(sess, stmt, replication);
		ensurePrivilege(sess, stmt);
		final TableCatalog prevCatalog = SqlUdfLookup.push(catalog);
		final SqlUdfCallContext prevCtx = SqlUdfCallContext.push(sess, this::execute);
		try {
			return dispatch(sess, stmt, expanded);
		} finally {
			SqlUdfCallContext.restore(prevCtx);
			SqlUdfLookup.restore(prevCatalog);
		}
	}

	/**
	 * Wire EXEC binds for {@code EXECUTE name} (no {@code USING} clause): prefer wire array.
	 */
	private static Stmt applyWireExecuteBinds(Stmt stmt, Object[] wireBinds) {
		if (!(stmt instanceof ExecuteSql exec)) {
			return stmt;
		}
		if (wireBinds == null || wireBinds.length == 0) {
			return stmt;
		}
		if (exec.binds() != null && exec.binds().length > 0) {
			return stmt;
		}
		return new ExecuteSql(exec.name(), wireBinds);
	}

	/**
	 * EXECUTE prepared: rebind cached PREPARE {@link Stmt} (no body ANTLR) then dispatch.
	 */
	private SqlResult executePrepared(SqlSession session, ExecuteSql exec) {
		final SqlSession.PreparedEntry entry = session.preparedEntry(exec.name());
		return executeCompiled(session, entry.bodyStmt(), entry.bodySql(), exec.binds());
	}

	/**
	 * Dispatch a CREATE TRIGGER / PREPARE body with positional binds — no body ANTLR.
	 * <p>
	 * Used by {@link org.genfork.grid.sql.exec.SqlTriggerFireOps} and EXECUTE prepared.
	 */
	public SqlResult executeCompiled(
			SqlSession session,
			Stmt template,
			String placeholderSql,
			Object[] binds
	) {
		GridProcessFence.checkAdmit("SqlEngine.executeCompiled");
		SqlTxMetrics.recordExecution();
		final SqlSession sess = session == null ? newSession() : session;
		final String effective = SqlBinds.materialize(placeholderSql, binds);
		final Stmt bound = SqlPreparedBinder.bind(template, effective, binds);
		SqlReplicaAdmission.afterParse(sess, bound, replication);
		ensurePrivilege(sess, bound);
		final TableCatalog prevCatalog = SqlUdfLookup.push(catalog);
		final SqlUdfCallContext prevCtx = SqlUdfCallContext.push(sess, this::execute);
		try {
			return dispatch(sess, bound, effective);
		} finally {
			SqlUdfCallContext.restore(prevCtx);
			SqlUdfLookup.restore(prevCatalog);
		}
	}

	/**
	 * Dispatch an already parsed and fully bound statement without re-running ANTLR.
	 */
	public SqlResult dispatchBound(SqlSession session, Stmt stmt) {
		GridProcessFence.checkAdmit("SqlEngine.dispatchBound");
		SqlTxMetrics.recordExecution();
		final SqlSession sess = session == null ? newSession() : session;
		SqlReplicaAdmission.afterParse(sess, stmt, replication);
		ensurePrivilege(sess, stmt);
		final TableCatalog prevCatalog = SqlUdfLookup.push(catalog);
		final SqlUdfCallContext prevCtx = SqlUdfCallContext.push(sess, this::execute);
		try {
			return dispatch(sess, stmt, "");
		} finally {
			SqlUdfCallContext.restore(prevCtx);
			SqlUdfLookup.restore(prevCatalog);
		}
	}

	private void ensurePrivilege(SqlSession session, Stmt stmt) {
		if (privileges.isOpen() || applyingReplicatedDdl.get()) {
			return;
		}
		if (!session.isAuthenticated() || !privileges.userExists(session.user())) {
			throw new SecurityException("permission denied: unauthenticated user " + session.user());
		}
		if (stmt instanceof CreateUserSql || stmt instanceof DropUserSql
				|| stmt instanceof AlterUserPasswordSql
				|| stmt instanceof CreateRoleSql || stmt instanceof DropRoleSql
				|| stmt instanceof GrantSql || stmt instanceof GrantToRoleSql
				|| stmt instanceof GrantRoleMembershipSql
				|| stmt instanceof RevokeSql) {
			privileges.ensure(session.user(), "*", "*", SqlPrivilege.DDL);
			return;
		}
		if (stmt instanceof SelectSql value) {
			if (value.table() != null && !value.table().isBlank()) {
				ensureTablePrivilege(session, value.table(), SqlPrivilege.SELECT);
			}
			if (value.joins() != null) {
				for (JoinEdge edge : value.joins()) {
					if (edge != null && edge.table() != null && !edge.table().isBlank()) {
						ensureTablePrivilege(session, edge.table(), SqlPrivilege.SELECT);
					}
				}
			}
		} else if (stmt instanceof InsertSql value) {
			ensureTablePrivilege(session, value.table(), SqlPrivilege.INSERT);
		} else if (stmt instanceof UpdateSql value) {
			ensureTablePrivilege(session, value.table(), SqlPrivilege.UPDATE);
			if (value.sourceTableOrNull() != null) {
				ensureTablePrivilege(session, value.sourceTableOrNull(), SqlPrivilege.SELECT);
			}
		} else if (stmt instanceof DeleteSql value) {
			ensureTablePrivilege(session, value.table(), SqlPrivilege.DELETE);
		} else if (stmt instanceof TruncateSql value) {
			ensureTablePrivilege(session, value.table(), SqlPrivilege.DELETE);
		} else if (stmt instanceof MergeSql value) {
			ensureTablePrivilege(session, value.targetTable(), SqlPrivilege.UPDATE);
		} else if (!(stmt instanceof BeginSql) && !(stmt instanceof CommitSql)
				&& !(stmt instanceof RollbackSql) && !(stmt instanceof PrepareSql)
				&& !(stmt instanceof SavepointSql) && !(stmt instanceof RollbackToSavepointSql)
				&& !(stmt instanceof ReleaseSavepointSql)
				&& !(stmt instanceof ExecuteSql) && !(stmt instanceof DeallocateSql)
				&& !(stmt instanceof SetSchemaSql) && !(stmt instanceof SetRemoteDirtySql)) {
			privileges.ensure(session.user(), session.currentSchema(), "*", SqlPrivilege.DDL);
		}
	}

	private void ensureTablePrivilege(SqlSession session, String rawTable, SqlPrivilege privilege) {
		final int separator = rawTable.indexOf('.');
		final String schema = separator < 0 ? session.currentSchema() : rawTable.substring(0, separator);
		final String table = separator < 0 ? rawTable : rawTable.substring(separator + 1);
		privileges.ensure(session.user(), schema, table, privilege);
	}

	private SqlResult dispatch(SqlSession session, Stmt stmt, String sql) {
		return switch (stmt) {
			case BeginSql ignored -> {
				session.beginTx();
				yield SqlResult.affected(SqlStatementTag.BEGIN, session.prepareHandle());
			}
			case CommitSql ignored -> {
				txCommitter.commit(session);
				yield SqlResult.ddl(SqlStatementTag.COMMIT);
			}
			case RollbackSql ignored -> {
				txCommitter.rollback(session);
				yield SqlResult.ddl(SqlStatementTag.ROLLBACK);
			}
			case SavepointSql s -> {
				session.savepoint(s.name());
				yield SqlResult.ddl(SqlStatementTag.SAVEPOINT);
			}
			case RollbackToSavepointSql s -> {
				session.rollbackToSavepoint(s.name());
				yield SqlResult.ddl(SqlStatementTag.ROLLBACK_TO_SAVEPOINT);
			}
			case ReleaseSavepointSql s -> {
				session.releaseSavepoint(s.name());
				yield SqlResult.ddl(SqlStatementTag.RELEASE_SAVEPOINT);
			}
			case PrepareSql s -> {
				final String body = s.bodySql().trim();
				final String expanded = SqlNamedQueryExpand.expand(body, catalog.viewSelectBodies());
				final Stmt bodyStmt = SqlStatementParser.parsePreparedBody(expanded, session.timezone());
				session.prepare(s.name(), expanded, bodyStmt);
				yield SqlResult.ddl(SqlStatementTag.PREPARE);
			}
			case ExecuteSql s -> executePrepared(session, s);
			case DeallocateSql s -> {
				session.deallocate(s.name());
				yield SqlResult.ddl(SqlStatementTag.DEALLOCATE);
			}
			case CreateUserSql s -> {
				rejectDdlInTx(session);
				// OpLog / sealed hydrate may replay CREATE USER after privilege meta already loaded.
				if (!(applyingReplicatedDdl.get() && privileges.userExists(s.user()))) {
					privileges.createUser(s.user(), s.password());
				}
				publishPrivilegeDdl(sql);
				yield SqlResult.ddl(SqlStatementTag.CREATE_USER);
			}
			case DropUserSql s -> {
				rejectDdlInTx(session);
				privileges.dropUser(s.user());
				publishPrivilegeDdl(sql);
				yield SqlResult.ddl(SqlStatementTag.DROP_USER);
			}
			case AlterUserPasswordSql s -> {
				rejectDdlInTx(session);
				privileges.changePassword(s.user(), s.password());
				publishPrivilegeDdl(sql);
				yield SqlResult.ddl(SqlStatementTag.ALTER_USER);
			}
			case CreateRoleSql s -> {
				rejectDdlInTx(session);
				if (!(applyingReplicatedDdl.get() && privileges.roleExists(s.role()))) {
					privileges.createRole(s.role());
				}
				publishPrivilegeDdl(sql);
				yield SqlResult.ddl(SqlStatementTag.CREATE_ROLE);
			}
			case DropRoleSql s -> {
				rejectDdlInTx(session);
				privileges.dropRole(s.role());
				publishPrivilegeDdl(sql);
				yield SqlResult.ddl(SqlStatementTag.DROP_ROLE);
			}
			case GrantSql s -> {
				rejectDdlInTx(session);
				final String schema = s.schemaScope() ? s.target() : session.currentSchema();
				final String table = s.schemaScope() ? "*" : s.target();
				privileges.grant(s.user(), schema, table, s.privileges());
				publishPrivilegeDdl(sql);
				yield SqlResult.ddl(SqlStatementTag.GRANT);
			}
			case GrantToRoleSql s -> {
				rejectDdlInTx(session);
				final String schema = s.schemaScope() ? s.target() : session.currentSchema();
				final String table = s.schemaScope() ? "*" : s.target();
				privileges.grantToRole(s.role(), schema, table, s.privileges());
				publishPrivilegeDdl(sql);
				yield SqlResult.ddl(SqlStatementTag.GRANT);
			}
			case GrantRoleMembershipSql s -> {
				rejectDdlInTx(session);
				privileges.grantRoleMembership(s.user(), s.role());
				publishPrivilegeDdl(sql);
				yield SqlResult.ddl(SqlStatementTag.GRANT);
			}
			case RevokeSql s -> {
				rejectDdlInTx(session);
				final String schema = s.schemaScope() ? s.target() : session.currentSchema();
				final String table = s.schemaScope() ? "*" : s.target();
				privileges.revoke(s.user(), schema, table, s.privileges());
				publishPrivilegeDdl(sql);
				yield SqlResult.ddl(SqlStatementTag.REVOKE);
			}
			case CreateTableSql s -> {
				rejectDdlInTx(session);
				yield ddl.createTable(session, s);
			}
			case CreateSequenceSql s -> {
				rejectDdlInTx(session);
				yield ddl.createSequence(session, s);
			}
			case DropSequenceSql s -> {
				rejectDdlInTx(session);
				yield ddl.dropSequence(session, s);
			}
			case SequenceValueSql s -> {
				ReplicaAccessGate.ensureWrite(replication);
				yield ddl.sequenceValue(session, s);
			}
			case DropTableSql s -> {
				rejectDdlInTx(session);
				session.invalidatePrepared();
				yield ddl.dropTable(session, s);
			}
			case CreateIndexSql s -> {
				rejectDdlInTx(session);
				session.invalidatePrepared();
				yield ddl.createIndex(session, s);
			}
			case DropIndexSql s -> {
				rejectDdlInTx(session);
				session.invalidatePrepared();
				yield ddl.dropIndex(session, s);
			}
			case CreateSchemaSql s -> {
				rejectDdlInTx(session);
				yield ddl.createSchema(s);
			}
			case DropSchemaSql s -> {
				rejectDdlInTx(session);
				yield ddl.dropSchema(s);
			}
			case SetSchemaSql s -> ddl.setSchema(session, s);
			case SetRemoteDirtySql s -> {
				session.setRemoteDirtyEnabled(s.enabled());
				yield SqlResult.ddl(SqlStatementTag.SET_REMOTE_DIRTY);
			}
			case AlterTableSql s -> {
				rejectDdlInTx(session);
				session.invalidatePrepared();
				yield ddl.alterTable(session, s);
			}
			case CreateViewSql s -> {
				rejectDdlInTx(session);
				yield ddl.createView(session, s);
			}
			case DropViewSql s -> {
				rejectDdlInTx(session);
				session.invalidatePrepared();
				yield ddl.dropView(session, s);
			}
			case CreateMaterializedViewSql s -> {
				rejectDdlInTx(session);
				yield ddl.createMaterializedView(session, s);
			}
			case RefreshMaterializedViewSql s -> {
				rejectDdlInTx(session);
				yield ddl.refreshMaterializedView(session, s);
			}
			case CreateFunctionSql s -> {
				rejectDdlInTx(session);
				yield createFunction(s);
			}
			case DropFunctionSql s -> {
				rejectDdlInTx(session);
				catalog.dropFunction(s.name(), s.ifExists());
				yield SqlResult.ddl(SqlStatementTag.DROP_FUNCTION);
			}
			case CreateTriggerSql s -> {
				rejectDdlInTx(session);
				session.invalidatePrepared();
				yield ddl.createTrigger(session, s);
			}
			case DropTriggerSql s -> {
				rejectDdlInTx(session);
				session.invalidatePrepared();
				yield ddl.dropTrigger(session, s);
			}
			case InsertSql s -> SqlAutocommit.runInUnit(session, txCommitter, () -> dml.insert(session, s));
			case MergeSql s -> SqlAutocommit.runInUnit(session, txCommitter, () -> dml.merge(session, s));
			case AnalyzeSql s -> dml.analyze(session, s);
			case DeleteSql s -> SqlAutocommit.runInUnit(session, txCommitter, () -> dml.delete(session, s));
			case TruncateSql s -> SqlAutocommit.runInUnit(session, txCommitter, () -> dml.truncate(session, s));
			case UpdateSql s -> SqlAutocommit.runInUnit(session, txCommitter, () -> dml.update(session, s));
			case SelectSql s -> {
				if (SqlUdfMutatingOps.selectHasMutatingUdf(s, catalog)) {
					yield SqlAutocommit.runInUnit(session, txCommitter, () -> query.select(session, s));
				}
				yield query.select(session, s);
			}
			case SetOpSql s -> {
				yield query.selectSetOp(session, s);
			}
			case RecursiveCteSql s -> {
				yield RecursiveCteExecutor.execute(
						session, query, tables, s, recursiveCteMaxDepth);
			}
			case ExplainSql s -> {
				if (s.query() instanceof SetOpSql u) {
					yield s.analyze()
							? query.explainAnalyzeSetOp(session, u)
							: query.explainSetOp(u);
				}
				if (s.query() instanceof RecursiveCteSql r) {
					final SqlResult executed = RecursiveCteExecutor.execute(
							session, query, tables, r, recursiveCteMaxDepth);
					yield s.analyze()
							? SqlResult.resultSet(
									List.of(
											SqlResult.ColumnMeta.of("plan", SqlType.VARCHAR),
											SqlResult.ColumnMeta.of("detail", SqlType.VARCHAR),
											SqlResult.ColumnMeta.of("extra", SqlType.VARCHAR)),
									List.<Object[]>of(new Object[]{
											"RECURSIVE_CTE",
											"rows=" + executed.rows().size(),
											"maxDepth=" + recursiveCteMaxDepth
									}))
							: SqlResult.resultSet(
									List.of(
											SqlResult.ColumnMeta.of("kind", SqlType.VARCHAR),
											SqlResult.ColumnMeta.of("table", SqlType.VARCHAR),
											SqlResult.ColumnMeta.of("detail", SqlType.VARCHAR)),
									List.<Object[]>of(new Object[]{
											"RECURSIVE_CTE",
											r.cteName(),
											"maxDepth=" + recursiveCteMaxDepth
									}));
				}
				if (s.query() instanceof SelectSql sel) {
					yield s.analyze()
							? query.explainAnalyze(session, sel)
							: query.explain(session, sel);
				}
				yield query.explainStatement(session, s.query());
			}
			case PinSql s -> pin(session, s);
			case UnpinSql s -> unpin(session, s);
		};
	}

	private SqlResult createFunction(CreateFunctionSql s) {
		if (catalog.getFunction(s.name()) != null) {
			if (applyingReplicatedDdl.get()) {
				return SqlResult.ddl(SqlStatementTag.CREATE_FUNCTION);
			}
			throw new IllegalStateException("Function already exists: " + s.name());
		}
		final List<SqlType> paramTypes = new ArrayList<>(s.paramTypeTokens().size());
		for (String token : s.paramTypeTokens()) {
			paramTypes.add(SqlType.fromToken(token));
		}
		if (s.returnsTable()) {
			final List<SqlType> tableTypes = new ArrayList<>(s.tableColumnTypeTokens().size());
			for (String token : s.tableColumnTypeTokens()) {
				tableTypes.add(SqlType.fromToken(token));
			}
			final SqlTableUdf tableUdf = SqlUdfBinder.bindTable(s.className(), s.methodName());
			catalog.createFunction(new FunctionDef(
					s.name(),
					List.copyOf(s.paramNames()),
					List.copyOf(paramTypes),
					null,
					FunctionKind.TABLE,
					List.copyOf(s.tableColumnNames()),
					List.copyOf(tableTypes),
					s.className(),
					s.methodName(),
					null,
					tableUdf,
					false
			));
		} else {
			final SqlType returnType = SqlType.fromToken(s.returnTypeToken());
			final SqlUdf udf = SqlUdfBinder.bind(s.className(), s.methodName());
			catalog.createFunction(new FunctionDef(
					s.name(),
					List.copyOf(s.paramNames()),
					List.copyOf(paramTypes),
					returnType,
					s.className(),
					s.methodName(),
					udf
			));
		}
		return SqlResult.ddl(SqlStatementTag.CREATE_FUNCTION);
	}

	private SqlResult pin(SqlSession session, PinSql s) {
		requireOverlayEnabled();
		final String table = tables.resolveTable(session, s.table());
		final TableStore store = tables.requireStore(table);
		final byte[] keyBytes = store.keyBytesForPk(s.keyLiteral());
		overlayStore.put(table, keyBytes, s.ttlMsOrNull(), true, s.qosTagOrNull());
		return SqlResult.ddl(SqlStatementTag.PIN);
	}

	private SqlResult unpin(SqlSession session, UnpinSql s) {
		requireOverlayEnabled();
		final String table = tables.resolveTable(session, s.table());
		final TableStore store = tables.requireStore(table);
		final byte[] keyBytes = store.keyBytesForPk(s.keyLiteral());
		overlayStore.remove(table, keyBytes);
		return SqlResult.ddl(SqlStatementTag.UNPIN);
	}

	private void requireOverlayEnabled() {
		if (overlayStore == null || !overlayStore.isEnabled()) {
			throw new IllegalStateException("overlay is disabled (grid.overlay.enabled=false)");
		}
	}

	private static void rejectDdlInTx(SqlSession session) {
		if (session.inTransaction()) {
			throw new IllegalStateException("DDL not allowed inside an open transaction");
		}
	}

	private void publishPrivilegeDdl(String sql) {
		if (applyingReplicatedDdl.get()) {
			return;
		}
		final long epoch = catalog.nextEpoch();
		catalog.noteAppliedDdlEpoch(epoch);
		if (replication != null && replication.isEnabled()) {
			replication.recordDdl(sql, epoch);
		}
	}

	/**
	 * Peer / proposer self-apply path for {@link org.genfork.grid.replication.codec.ReplicationOpType#DDL}.
	 */
	public void applyReplicatedDdl(String ddlSql, long schemaEpoch) {
		if (ddlSql == null || ddlSql.isBlank()) {
			throw new IllegalArgumentException("replicated DDL is empty");
		}
		catalog.rejectIfStaleDdlEpoch(schemaEpoch);
		if (!applyingReplicatedDdl.compareAndSet(false, true)) {
			throw new IllegalStateException("nested replicated DDL apply");
		}
		try {
			execute(ddlSql);
			if (schemaEpoch > 0L) {
				catalog.noteAppliedDdlEpoch(schemaEpoch);
				catalog.ensureEpochAtLeast(schemaEpoch);
			}
		} finally {
			applyingReplicatedDdl.set(false);
		}
	}

	/**
	 * Replay catalog snapshot after process restart:
	 * {@code schemas.list} → {@code ddl.sql} → missing tables from {@code *.meta}.
	 * Skips re-append / OpLog publish. Row data durability is outside this catalog path
	 * (in-memory / replication OpLog); meta+DDL keep schema aligned with disk.
	 */
	public void recoverPersistedCatalog() {
		final List<String> named = catalog.loadPersistedNamedSchemas();
		final List<String> lines = catalog.loadPersistedDdl();
		final List<TableSchema> metas = catalog.loadPersistedMetaSchemas();
		if (named.isEmpty() && lines.isEmpty() && metas.isEmpty()) {
			return;
		}
		if (!applyingReplicatedDdl.compareAndSet(false, true)) {
			throw new IllegalStateException("cannot recover while applying replicated DDL");
		}
		catalog.beginDdlReplay();
		try {
			for (String schemaName : named) {
				if (!catalog.schemaExists(schemaName)) {
					catalog.createSchema(schemaName, true);
				}
			}
			for (String line : lines) {
				if (line == null || line.isBlank()) {
					continue;
				}
				execute(line.trim());
			}
			for (TableSchema meta : metas) {
				if (!catalog.shouldHydrateMeta(meta)) {
					catalog.deletePersistedTableMeta(meta.tableName());
					continue;
				}
				if (catalog.exists(meta.tableName())) {
					catalog.ensureEpochAtLeast(meta.schemaEpoch());
					continue;
				}
				catalog.createTable(meta);
				catalog.ensureEpochAtLeast(meta.schemaEpoch());
			}
			catalog.pruneOrphanMetaFiles();
		} finally {
			catalog.endDdlReplay();
			applyingReplicatedDdl.set(false);
		}
		catalog.rewriteCompactedDdl();
	}

	/** Test hook: privilege catalog snapshot bytes. */
	@VisibleForTesting
	public byte[] privilegeSnapshotBytes() {
		return privileges.toSnapshotBytes();
	}

	/** Test hook: replace privilege catalog from snapshot bytes. */
	@VisibleForTesting
	public void loadPrivilegeSnapshotBytes(byte[] bytes) {
		privileges.loadSnapshotBytes(bytes);
	}
}
