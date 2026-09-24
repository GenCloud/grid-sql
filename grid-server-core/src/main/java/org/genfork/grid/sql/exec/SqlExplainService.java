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

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.query.adaptive.QueryHeaviness;
import org.genfork.grid.query.adaptive.QueryHeavinessEstimator;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.plan.ExplainQuery;
import org.genfork.grid.query.plan.QueryCardinality;
import org.genfork.grid.query.plan.QueryData;
import org.genfork.grid.query.plan.QueryOptimizer;
import org.genfork.grid.query.plan.QueryParser;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.ast.DmlAst.AnalyzeSql;
import org.genfork.grid.sql.ast.DdlAst.CreateTableSql;
import org.genfork.grid.sql.ast.DmlAst.DeleteSql;
import org.genfork.grid.sql.ast.DmlAst.InsertSql;
import org.genfork.grid.sql.ast.SelectAst.JoinEdge;
import org.genfork.grid.sql.ast.DmlAst.MergeSql;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.genfork.grid.sql.ast.SelectAst.SetOpSql;
import org.genfork.grid.sql.ast.Stmt;
import org.genfork.grid.sql.ast.DmlAst.UpdateSql;
import org.genfork.grid.store.TableStore;

/**
 * EXPLAIN / EXPLAIN ANALYZE for SELECT and set ops (delegated from {@link SqlQueryExecutor}).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlExplainService {
	/** Nanoseconds per microsecond for EXPLAIN ANALYZE elapsedUs. */
	private static final long NANOS_PER_MICRO = 1_000L;
	/** EXPLAIN detail prefix when a composite index applies at plan time. */
	private static final String COMPOSITE_INDEX_DETAIL_PREFIX = "compositeIndex=";
	private static final String STMT_SUFFIX = "Sql";
	private static final String CAMEL_BOUNDARY = "(?<=[a-z0-9])(?=[A-Z])";
	private static final String DRY_PLAN_DETAIL = "dry=true; side-effects=none";

	private final SqlTableResolver tables;
	private final CrossDomainTableResolver domains;
	private final SqlQueryExecutor query;

	/**
	 * @param tables  table / peer resolver
	 * @param domains cross-domain JOIN resolver
	 * @param query   executor used for ANALYZE execution
	 */
	public SqlExplainService(
			SqlTableResolver tables,
			CrossDomainTableResolver domains,
			SqlQueryExecutor query
	) {
		this.tables = tables;
		this.domains = domains;
		this.query = query;
	}

	/**
	 * Lightweight EXPLAIN: plan kind without executing.
	 */
	public SqlResult explain(SqlSession session, SelectSql s) {
		final List<SqlResult.ColumnMeta> metas = List.of(
				SqlResult.ColumnMeta.of("kind", SqlType.VARCHAR),
				SqlResult.ColumnMeta.of("table", SqlType.VARCHAR),
				SqlResult.ColumnMeta.of("detail", SqlType.VARCHAR)
		);

		if (s.hasJoins()) {
			final JoinEdge first = s.joins().getFirst();
			final String leftName = domains.resolve(session, s.table());
			final String rightName = domains.resolve(session, first.table());
			final TableStore left = domains.requireStore(leftName);
			final TableStore right = domains.requireStore(rightName);
			final ColumnDef leftCol = left.schema().requireColumn(first.leftCol());
			final ColumnDef rightCol = right.schema().requireColumn(first.rightCol());
			final boolean leftOnPk = leftCol.primaryKey();
			final boolean rightOnPk = rightCol.primaryKey();
			final QueryOptimizer.JoinChoice joinChoice = QueryOptimizer.chooseJoinStrategy(
					leftOnPk,
					rightOnPk,
					left.approxRowStats().estimatedRows(),
					right.approxRowStats().estimatedRows()
			);
			final String localKind = joinChoice.kind();
			final List<Function<String, List<byte[]>>> peers = tables.distributedPeerKeyExecutors();
			final boolean dist = peers != null && !peers.isEmpty();
			final String kind = dist ? SqlExplainKinds.JOIN_DIST_FANOUT : localKind;
			final String joinLabel = SqlJoinOps.explainJoinLabel(first.kind());
			final String detail;
			if (SqlExplainKinds.JOIN_PK.equals(localKind) && rightOnPk) {
				detail = (dist ? "build fan-in; local " + localKind + "; " : "")
						+ "probe right pk=" + rightCol.name() + " " + joinLabel
						+ " estCost=" + String.format(Locale.ROOT, "%.2f", joinChoice.estimatedCost());
			} else if (SqlExplainKinds.JOIN_PK.equals(localKind) && leftOnPk) {
				detail = (dist ? "build fan-in; local " + localKind + "; " : "")
						+ "probe left pk=" + leftCol.name() + " " + joinLabel
						+ " estCost=" + String.format(Locale.ROOT, "%.2f", joinChoice.estimatedCost());
			} else {
				detail = (dist ? "build fan-in; local " + localKind + "; " : "")
						+ leftCol.name() + "=" + rightCol.name() + " " + joinLabel
						+ " estCost=" + String.format(Locale.ROOT, "%.2f", joinChoice.estimatedCost());
			}
			final String detailWithTx = dist && session.inTransaction()
					? detail + " " + (session.remoteDirtyEnabled()
							? SqlExplainKinds.TX_MODE_REMOTE_DIRTY_FANOUT
							: SqlExplainKinds.TX_MODE_COMMITTED_FANOUT)
					: detail;
			final StringBuilder tablesLabel = new StringBuilder(leftName).append(',').append(rightName);
			for (int i = 1; i < s.joins().size(); i++) {
				tablesLabel.append(',')
						.append(domains.resolve(session, s.joins().get(i).table()));
			}
			return SqlResult.resultSet(metas, Collections.singletonList(
					new Object[]{kind, tablesLabel.toString(), detailWithTx}));
		}

		final String table = tables.resolveTable(session, s.table());
		final TableStore store = tables.requireStore(table);
		if (s.pkColumnOrNull() != null
				&& store.schema().pkColumn().name().equalsIgnoreCase(s.pkColumnOrNull())) {
			return SqlResult.resultSet(metas, Collections.singletonList(
					new Object[]{SqlExplainKinds.INDEX, table, "pk=" + s.pkColumnOrNull()}));
		}

		if (s.aggregate()) {
			final StringBuilder agg = new StringBuilder("aggregate");
			if (s.countStar()) {
				agg.append(" COUNT(*)");
			} else if (s.avg()) {
				agg.append(" AVG(").append(s.sumColumnOrNull()).append(')');
			} else if (s.sumColumnOrNull() != null) {
				agg.append(" SUM(").append(s.sumColumnOrNull()).append(')');
			}
			return SqlResult.resultSet(metas, Collections.singletonList(
					new Object[]{SqlExplainKinds.INDEX, table, agg.toString()}));
		}

		final String compositeName = store.compositeIndex().findApplicableCompositeIndexName(s.sql());
		final StringBuilder detail = new StringBuilder("filter/scan stats=")
				.append(store.approxRowStats().source())
				.append(" estRows=")
				.append(store.approxRowStats().estimatedRows());
		final QueryData parsed = QueryParser.parseAndBuildCondition(null, s.sql(), Map.of());
		final FilterCondition filter = parsed.filter() == null ? null : parsed.filter().conditionTree();
		final long histogramRows = QueryOptimizer.histogramEqualityEstimate(store.analyzeStatsOrNull(), filter);
		if (histogramRows > 0L) {
			detail.append(" histogram=true histogramEstRows=").append(histogramRows);
		}
		final QueryHeaviness heaviness = QueryHeavinessEstimator.fromFilter(store, filter, false);
		detail.append(" predictedCandidates=").append(heaviness.predictedCandidates());
		String kind = SqlExplainKinds.INDEX;
		if (heaviness.isDistributedHeavy(store.shardCount())) {
			kind = SqlExplainKinds.DIST_MAP;
			detail.append(" shards=").append(store.shardCount())
					.append(" floor=").append(QueryHeaviness.distributedCandidateThreshold());
		} else if (heaviness.isHeavy()) {
			kind = SqlExplainKinds.AQE_PARALLEL;
			detail.append(" aqeFloor=").append(QueryHeaviness.heavyCandidateThreshold());
		}
		if (compositeName != null && !compositeName.isBlank()) {
			detail.append(' ').append(COMPOSITE_INDEX_DETAIL_PREFIX).append(compositeName);
		}
		return SqlResult.resultSet(metas, Collections.singletonList(
				new Object[]{kind, table, detail.toString()}));
	}

	/**
	 * Side-effect-free plan-kind row for DML, DDL, and other non-query statements.
	 * EXPLAIN ANALYZE intentionally remains dry for mutating bodies.
	 */
	public SqlResult explainStatement(SqlSession session, Stmt statement) {
		final List<SqlResult.ColumnMeta> metas = List.of(
				SqlResult.ColumnMeta.of("kind", SqlType.VARCHAR),
				SqlResult.ColumnMeta.of("table", SqlType.VARCHAR),
				SqlResult.ColumnMeta.of("detail", SqlType.VARCHAR)
		);
		return SqlResult.resultSet(metas, Collections.singletonList(new Object[]{
				statementKind(statement),
				statementTable(session, statement),
				DRY_PLAN_DETAIL
		}));
	}

	private String statementTable(SqlSession session, Stmt statement) {
		final String table;
        switch (statement) {
            case InsertSql insert -> table = insert.table();
            case UpdateSql update -> table = update.table();
            case DeleteSql delete -> table = delete.table();
            case MergeSql merge -> table = merge.targetTable();
            case AnalyzeSql analyze -> table = analyze.table();
            case CreateTableSql createTable -> table = createTable.table();
            case null, default -> {
                return "";
            }
        }
		return tables.resolveTable(session, table);
	}

	private static String statementKind(Stmt statement) {
		String name = statement.getClass().getSimpleName();
		if (name.endsWith(STMT_SUFFIX)) {
			name = name.substring(0, name.length() - STMT_SUFFIX.length());
		}
		return name.replaceAll(CAMEL_BOUNDARY, "_").toUpperCase(Locale.ROOT);
	}

	/**
	 * Execute SELECT and return {@link ExplainQuery} plan rows (kind, strategy, detail).
	 */
	public SqlResult explainAnalyze(SqlSession session, SelectSql s) {
		final List<SqlResult.ColumnMeta> metas = List.of(
				SqlResult.ColumnMeta.of("kind", SqlType.VARCHAR),
				SqlResult.ColumnMeta.of("strategy", SqlType.VARCHAR),
				SqlResult.ColumnMeta.of("detail", SqlType.VARCHAR)
		);
		final ExplainQuery.QueryPlan plan = new ExplainQuery.QueryPlan("SQL EXPLAIN ANALYZE");
		long rowsReturned;
		try {
			if (s.hasJoins() || s.hasWindow() || s.aggregate()) {
				final SqlResult executed = query.select(session, s);
				rowsReturned = executed.rows().size();
				ExplainQuery.startNode(plan, "EXECUTE", s.sql());
				ExplainQuery.endNode(plan.getRootNode(), -1, rowsReturned);
			} else {
				final String table = tables.resolveTable(session, s.table());
				final TableStore store = tables.requireStore(table);
				final List<byte[]> keys = store.compositeIndex().executeStatement(plan, s.sql());
				rowsReturned = keys == null ? 0L : keys.size();
			}
			ExplainQuery.finishAnalyzePlan(plan, rowsReturned);
			if (plan.getEstimatedCost() < 0.0) {
				final TableStore store = tables.requireStore(tables.resolveTable(session, s.table()));
				final double fallback = QueryCardinality.costTableScan(store.approxRowStats().estimatedRows());
				ExplainQuery.recordEstimatedCost(plan, fallback);
			}
		} catch (RuntimeException ex) {
			ExplainQuery.markAnalyzeFailed(plan, ex.getMessage());
			throw ex;
		}
		final String strategy = tables.resolveTable(session, s.table());
		return SqlResult.resultSet(metas, ExplainQuery.toResultRows(plan, strategy));
	}

	public SqlResult explainSetOp(SetOpSql u) {
		final List<SqlResult.ColumnMeta> metas = List.of(
				SqlResult.ColumnMeta.of("plan", SqlType.VARCHAR),
				SqlResult.ColumnMeta.of("detail", SqlType.VARCHAR),
				SqlResult.ColumnMeta.of("extra", SqlType.VARCHAR));
		final StringBuilder kinds = new StringBuilder();
		for (int i = 0; i < u.opsBetween().size(); i++) {
			if (i > 0) {
				kinds.append(',');
			}
			kinds.append(u.opsBetween().get(i).name());
			kinds.append(u.allBetween().get(i) ? "_ALL" : "");
		}
		return SqlResult.resultSet(metas, Collections.singletonList(
				new Object[]{"SET_OP", "arms=" + u.arms().size(), kinds.toString()}));
	}

	public SqlResult explainAnalyzeSetOp(SqlSession session, SetOpSql u) {
		final long t0 = System.nanoTime();
		final SqlResult result = query.selectSetOp(session, u);
		final long micros = (System.nanoTime() - t0) / NANOS_PER_MICRO;
		final List<SqlResult.ColumnMeta> metas = List.of(
				SqlResult.ColumnMeta.of("plan", SqlType.VARCHAR),
				SqlResult.ColumnMeta.of("detail", SqlType.VARCHAR),
				SqlResult.ColumnMeta.of("extra", SqlType.VARCHAR));
		return SqlResult.resultSet(metas, Collections.singletonList(
				new Object[]{
						"SET_OP",
						"arms=" + u.arms().size() + " rows=" + result.rows().size(),
						"elapsedUs=" + micros
				}));
	}
}
