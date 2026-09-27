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

import java.util.List;
import java.util.Objects;

import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.catalog.TriggerDef;
import org.genfork.grid.catalog.TriggerEvent;
import org.genfork.grid.catalog.TriggerGranularity;
import org.genfork.grid.catalog.TriggerTiming;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.ast.Stmt;
import org.genfork.grid.store.TableStore;

/**
 * Row/statement trigger fire: wire blobs + bound {@link Stmt} dispatch + auto-SAVEPOINT.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlTriggerFireOps {
	static final int MAX_TRIGGER_NESTING = 8;
	private static final String AUTO_SAVEPOINT_PREFIX = "__trg_";
	private static final String SQL_TRUE = "TRUE";
	private static final String SQL_FALSE = "FALSE";
	private static final String ONE = "1";
	private static final String ZERO = "0";

	private SqlTriggerFireOps() {
	}

	static void fireBeforeRow(
			SqlSession session,
			SqlEngine engine,
			String table,
			TriggerEvent event,
			byte[] oldBlob,
			byte[] newBlob
	) {
		fire(session, engine, table, TriggerTiming.BEFORE, event, TriggerGranularity.ROW, oldBlob, newBlob);
	}

	static void fireAfterRow(
			SqlSession session,
			SqlEngine engine,
			String table,
			TriggerEvent event,
			byte[] oldBlob,
			byte[] newBlob
	) {
		fire(session, engine, table, TriggerTiming.AFTER, event, TriggerGranularity.ROW, oldBlob, newBlob);
	}

	static void fireBeforeStatement(
			SqlSession session,
			SqlEngine engine,
			String table,
			TriggerEvent event
	) {
		fire(session, engine, table, TriggerTiming.BEFORE, event, TriggerGranularity.STATEMENT, null, null);
	}

	static void fireAfterStatement(
			SqlSession session,
			SqlEngine engine,
			String table,
			TriggerEvent event
	) {
		fire(session, engine, table, TriggerTiming.AFTER, event, TriggerGranularity.STATEMENT, null, null);
	}

	private static void fire(
			SqlSession session,
			SqlEngine engine,
			String table,
			TriggerTiming timing,
			TriggerEvent event,
			TriggerGranularity granularity,
			byte[] oldBlob,
			byte[] newBlob
	) {
		Objects.requireNonNull(session, "session");
		Objects.requireNonNull(engine, "engine");
		Objects.requireNonNull(table, "table");
		final List<TriggerDef> defs = engine.catalog().triggersForTable(table);
		if (defs.isEmpty()) {
			return;
		}
		final TableStore store = engine.catalog().getStore(table);
		final TableSchema schema = store == null ? null : store.schema();
		for (TriggerDef def : defs) {
			if (def.timing() != timing || def.event() != event || def.granularity() != granularity) {
				continue;
			}
			if (!whenAllows(session, def, schema, oldBlob, newBlob)) {
				continue;
			}
			final Stmt bound = granularity == TriggerGranularity.ROW
					? SqlTriggerBindUtil.bindTrigger(def.bodyStmt(), schema, oldBlob, newBlob)
					: def.bodyStmt();
			runBody(session, engine, bound);
		}
	}

	private static void runBody(SqlSession session, SqlEngine engine, Stmt bound) {
		enterNesting(session);
		final boolean inTx = session.inTransaction();
		final String spName = AUTO_SAVEPOINT_PREFIX + session.triggerNestingDepth();
		if (inTx) {
			session.savepoint(spName);
		}
		try {
			engine.dispatchBound(session, bound);
			if (inTx) {
				session.releaseSavepoint(spName);
			}
		} catch (RuntimeException ex) {
			if (inTx) {
				session.rollbackToSavepoint(spName);
				session.releaseSavepoint(spName);
			}
			throw ex;
		} finally {
			exitNesting(session);
		}
	}

	private static boolean whenAllows(
			SqlSession session,
			TriggerDef def,
			TableSchema schema,
			byte[] oldBlob,
			byte[] newBlob
	) {
		final String whenSql = def.whenSqlOrNull();
		if (whenSql == null || whenSql.isBlank()) {
			return true;
		}
		final String substituted = whenSql.trim();
		if (SQL_TRUE.equalsIgnoreCase(substituted) || ONE.equals(substituted)) {
			return true;
		}
		if (SQL_FALSE.equalsIgnoreCase(substituted) || ZERO.equals(substituted)) {
			return false;
		}
		if (schema == null) {
			throw new IllegalStateException("trigger table schema unavailable");
		}
		if (oldBlob == null && newBlob == null) {
			throw new IllegalArgumentException("STATEMENT trigger WHEN must be a constant expression");
		}
		final byte[] probe = newBlob != null ? newBlob : oldBlob;
		final String boundExpr = SqlTriggerBindUtil.rewriteExpressionText(substituted, schema, oldBlob, newBlob);
		return SqlExpressionFilterUtil.matchesExpression(boundExpr, probe, schema);
	}

	private static void enterNesting(SqlSession session) {
		final int depth = session.enterTriggerNesting();
		if (depth > MAX_TRIGGER_NESTING) {
			session.exitTriggerNesting();
			throw new IllegalStateException(
					"trigger nesting exceeded max " + MAX_TRIGGER_NESTING);
		}
	}

	private static void exitNesting(SqlSession session) {
		session.exitTriggerNesting();
	}
}