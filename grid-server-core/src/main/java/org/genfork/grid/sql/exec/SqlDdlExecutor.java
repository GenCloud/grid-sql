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
import org.genfork.grid.catalog.CheckDef;
import org.genfork.grid.sql.ast.DdlAst.SequenceValueSql;
import org.genfork.grid.sql.ast.DdlAst.FkSpec;
import org.genfork.grid.sql.ast.DdlAst.DropSequenceSql;
import org.genfork.grid.sql.ast.DdlAst.CreateSequenceSql;
import org.genfork.grid.catalog.SequenceDef;
import org.genfork.grid.catalog.FkDef;
import org.genfork.grid.catalog.FkAction;
import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.catalog.TableCatalog.ViewDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.catalog.TriggerDef;
import org.genfork.grid.catalog.TriggerEvent;
import org.genfork.grid.catalog.TriggerGranularity;
import org.genfork.grid.catalog.TriggerTiming;
import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlNamedQueryExpand;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.SqlStatementParser;
import org.genfork.grid.sql.ast.DdlAst.AlterTableSql;
import org.genfork.grid.sql.ast.DdlAst.CheckSpec;
import org.genfork.grid.sql.ast.DdlAst.ColumnSpec;
import org.genfork.grid.sql.ast.DdlAst.CreateIndexSql;
import org.genfork.grid.sql.ast.DdlAst.CreateMaterializedViewSql;
import org.genfork.grid.sql.ast.DdlAst.CreateSchemaSql;
import org.genfork.grid.sql.ast.DdlAst.CreateTableSql;
import org.genfork.grid.sql.ast.DdlAst.CreateTriggerSql;
import org.genfork.grid.sql.ast.DdlAst.CreateViewSql;
import org.genfork.grid.sql.ast.DdlAst.DropIndexSql;
import org.genfork.grid.sql.ast.DdlAst.DropSchemaSql;
import org.genfork.grid.sql.ast.DdlAst.DropTableSql;
import org.genfork.grid.sql.ast.DdlAst.DropTriggerSql;
import org.genfork.grid.sql.ast.DdlAst.DropViewSql;
import org.genfork.grid.sql.ast.DdlAst.RefreshMaterializedViewSql;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.genfork.grid.sql.ast.DdlAst.SetSchemaSql;
import org.genfork.grid.sql.ast.Stmt;
import org.genfork.grid.sql.SqlStatementTag;
import org.genfork.grid.store.TableStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Catalog DDL + schema session ops for {@link org.genfork.grid.sql.SqlEngine}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlDdlExecutor {
	private static final String CHECK_NAME_INFIX = "_check_";
	private final SqlTableResolver tables;
	private final AtomicBoolean applyingReplicatedDdl;
	private final SqlQueryExecutor query;

	public SqlDdlExecutor(SqlTableResolver tables, AtomicBoolean applyingReplicatedDdl) {
		this(tables, applyingReplicatedDdl, null);
	}

	public SqlDdlExecutor(
			SqlTableResolver tables,
			AtomicBoolean applyingReplicatedDdl,
			SqlQueryExecutor query
	) {
		this.tables = tables;
		this.applyingReplicatedDdl = applyingReplicatedDdl;
		this.query = query;
	}

	public SqlResult createTable(SqlSession session, CreateTableSql s) {
		final TableCatalog catalog = tables.catalog();
		final String table = tables.resolveTable(session, s.table());
		if (catalog.exists(table)) {
			if (s.ifNotExists() || applyingReplicatedDdl.get()) {
				if (catalog.getStore(table) == null) {
					final TableSchema existing = catalog.getSchema(table);
					if (existing != null) {
						tables.ensureStore(existing);
					}
				}
				return SqlResult.ddl(SqlStatementTag.CREATE_TABLE);
			}
			throw new IllegalStateException("Table already exists: " + table);
		}
		final TableSchema.Builder b = TableSchema.builder(table).schemaEpoch(catalog.nextEpoch());
		final List<SequenceDef> identitySequences = new ArrayList<>();
		for (ColumnSpec col : s.columns()) {
			final SqlType type = SqlType.fromToken(col.typeToken());
			final boolean identity = col.identity() || col.serial();
			if (identity) {
				final String seqName = SequenceDef.identityName(table, col.name());
				identitySequences.add(SequenceDef.of(seqName, SequenceDef.DEFAULT_START,
						SequenceDef.DEFAULT_INCREMENT, false).withOwner(table, col.name()));
				b.identityColumn(col.name(), type, col.primaryKey() || containsColumn(s.tablePk(), col.name()), seqName);
			} else if (col.primaryKey()) {
				b.primaryKey(col.name(), type);
			} else {
				b.column(col.name(), type, !col.notNull());
			}
		}
		for (String pkColumn : s.tablePk()) {
			b.markPrimaryKey(pkColumn);
		}
		if (s.foreignKeys() != null) {
			for (FkSpec fk : s.foreignKeys()) {
				final String fkName = fk.nameOrNull() == null || fk.nameOrNull().isBlank()
						? FkDef.defaultName(table, fk.childColumns())
						: fk.nameOrNull();
				b.foreignKey(new FkDef(
						fkName,
						table,
						fk.childColumns(),
						tables.resolveTable(session, fk.parentTable()),
						fk.parentColumns(),
						FkAction.fromToken(fk.onDeleteOrNull()),
						FkAction.fromToken(fk.onUpdateOrNull())));
			}
		}
		if (s.checks() != null) {
			int checkOrdinal = 1;
			for (CheckSpec check : s.checks()) {
				final String checkName = check.nameOrNull() == null || check.nameOrNull().isBlank()
						? table + CHECK_NAME_INFIX + checkOrdinal
						: check.nameOrNull();
				b.check(new CheckDef(checkName, check.expressionSql()));
				checkOrdinal++;
			}
		}
		final TableSchema schema;
		try {
			for (SequenceDef seq : identitySequences) {
				catalog.createSequence(seq, true);
			}
			final TableSchema built = b.build();
			validateSetNullFk(built);
			schema = catalog.createTable(built);
		} catch (IllegalStateException | IllegalArgumentException ex) {
			if (s.ifNotExists() && catalog.exists(table)) {
				if (catalog.getStore(table) == null) {
					final TableSchema existing = catalog.getSchema(table);
					if (existing != null) {
						tables.ensureStore(existing);
					}
				}
				return SqlResult.ddl(SqlStatementTag.CREATE_TABLE);
			}
			throw ex;
		}
		tables.ensureStore(schema);
		final String persist = SqlDdlRender.createTable(s, table);
		catalog.appendDdl(persist);
		publishDdl(persist, schema.schemaEpoch());
		return SqlResult.ddl(SqlStatementTag.CREATE_TABLE);
	}

	private static boolean containsColumn(List<String> columns, String expected) {
		for (String column : columns) {
			if (column.equalsIgnoreCase(expected)) {
				return true;
			}
		}
		return false;
	}

	private static void validateSetNullFk(TableSchema schema) {
		for (FkDef fk : schema.foreignKeys()) {
			if (fk.onDelete() != FkAction.SET_NULL && fk.onUpdate() != FkAction.SET_NULL) {
				continue;
			}
			for (String col : fk.childColumns()) {
				final ColumnDef def = schema.requireColumn(col);
				if (!def.nullable()) {
					throw new IllegalArgumentException(
							"FOREIGN KEY SET NULL requires nullable child column" + ": " + fk.name() + "." + col);
				}
			}
		}
	}

	public SqlResult createSequence(SqlSession session, CreateSequenceSql s) {
		final TableCatalog catalog = tables.catalog();
		final String name = tables.resolveTable(session, s.name());
		final SequenceDef def = SequenceDef.of(name, s.startValue(), s.increment(), s.reclaim());
		final boolean created = catalog.createSequence(def, s.ifNotExists() || applyingReplicatedDdl.get());
		if (!created && !(s.ifNotExists() || applyingReplicatedDdl.get())) {
			throw new IllegalStateException("Sequence already exists: " + name);
		}
		if (created) {
			final String persist = SqlDdlRender.createSequence(s, name);
			catalog.appendDdl(persist);
			publishDdl(persist, catalog.nextEpoch());
		}
		return SqlResult.ddl(SqlStatementTag.CREATE_SEQUENCE);
	}

	public SqlResult dropSequence(SqlSession session, DropSequenceSql s) {
		final TableCatalog catalog = tables.catalog();
		final String name = tables.resolveTable(session, s.name());
		catalog.dropSequence(name, s.ifExists() || applyingReplicatedDdl.get());
		final String persist = SqlDdlRender.dropSequence(s, name);
		catalog.appendDdl(persist);
		publishDdl(persist, catalog.nextEpoch());
		return SqlResult.ddl(SqlStatementTag.DROP_SEQUENCE);
	}

	public SqlResult sequenceValue(SqlSession session, SequenceValueSql s) {
		final TableCatalog catalog = tables.catalog();
		final String name = tables.resolveTable(session, s.sequenceName());
		final long value;
		if (s.nextVal()) {
			value = catalog.nextVal(name);
			session.rememberSequenceValue(name, value);
		} else {
			value = session.currval(name);
		}
		return SqlResult.resultSet(
				List.of(SqlResult.ColumnMeta.of(s.nextVal() ? "nextval" : "currval", SqlType.BIGINT)),
				List.<Object[]>of(new Object[]{Long.valueOf(value)}));
	}

	public SqlResult dropTable(SqlSession session, DropTableSql s) {
		final TableCatalog catalog = tables.catalog();
		final String table = tables.resolveTable(session, s.table());
		if (!catalog.exists(table)) {
			if (s.ifExists() || applyingReplicatedDdl.get()) {
				return SqlResult.ddl(SqlStatementTag.DROP_TABLE);
			}
			throw new IllegalStateException("Table not found: " + table);
		}
		final long epoch = catalog.nextEpoch();
		catalog.dropTable(table);
		final String persist = SqlDdlRender.dropTable(s, table);
		catalog.appendDdl(persist);
		publishDdl(persist, epoch);
		return SqlResult.ddl(SqlStatementTag.DROP_TABLE);
	}

	public SqlResult createIndex(SqlSession session, CreateIndexSql s) {
		final TableCatalog catalog = tables.catalog();
		final TableStore store = tables.requireStore(tables.resolveTable(session, s.table()));
		for (String col : s.columns()) {
			store.schema().requireColumn(col);
		}
		if (s.kind() == IndexType.BITMAP && s.columns().size() > 1) {
			throw new IllegalArgumentException(IndexDef.BITMAP_SINGLE_COLUMN_ONLY);
		}
		if (store.schema().findIndex(s.indexName()) != null || store.index().hasNamedIndex(s.indexName())) {
			if (applyingReplicatedDdl.get()) {
				return SqlResult.ddl(SqlStatementTag.CREATE_INDEX);
			}
			throw new IllegalStateException("Index already exists: " + s.indexName());
		}
		final IndexDef def = IndexDef.of(s.indexName(), s.kind(), s.columns().toArray(String[]::new));
		final long epoch = catalog.nextEpoch();
		store.addIndex(def, epoch);
		catalog.replaceSchema(store.schema());
		final String persist = SqlDdlRender.createIndex(s, store.schema().tableName());
		catalog.appendDdl(persist);
		publishDdl(persist, epoch);
		return SqlResult.ddl(SqlStatementTag.CREATE_INDEX);
	}

	public SqlResult dropIndex(SqlSession session, DropIndexSql s) {
		final TableCatalog catalog = tables.catalog();
		final String table = s.tableOrNull();
		if (table == null) {
			throw new IllegalArgumentException("DROP INDEX requires ON table");
		}
		final TableStore store = tables.requireStore(tables.resolveTable(session, table));
		if (store.schema().findIndex(s.indexName()) == null && !store.index().hasNamedIndex(s.indexName())) {
			if (applyingReplicatedDdl.get()) {
				return SqlResult.ddl(SqlStatementTag.DROP_INDEX);
			}
			throw new IllegalStateException("Index not found: " + s.indexName());
		}
		final long epoch = catalog.nextEpoch();
		store.dropIndex(s.indexName(), epoch);
		catalog.replaceSchema(store.schema());
		final String persist = SqlDdlRender.dropIndex(s, store.schema().tableName());
		catalog.appendDdl(persist);
		publishDdl(persist, epoch);
		return SqlResult.ddl(SqlStatementTag.DROP_INDEX);
	}

	public SqlResult createSchema(CreateSchemaSql s) {
		final TableCatalog catalog = tables.catalog();
		if (catalog.schemaExists(s.schema())) {
			if (s.ifNotExists() || applyingReplicatedDdl.get()) {
				return SqlResult.ddl(SqlStatementTag.CREATE_SCHEMA);
			}
			throw new IllegalStateException("Schema already exists: " + s.schema());
		}
		final long epoch = catalog.nextEpoch();
		catalog.createSchema(s.schema(), false);
		final String persist = SqlDdlRender.createSchema(s);
		catalog.appendDdl(persist);
		publishDdl(persist, epoch);
		return SqlResult.ddl(SqlStatementTag.CREATE_SCHEMA);
	}

	public SqlResult dropSchema(DropSchemaSql s) {
		final TableCatalog catalog = tables.catalog();
		if (!catalog.schemaExists(s.schema())) {
			if (s.ifExists() || applyingReplicatedDdl.get()) {
				return SqlResult.ddl(SqlStatementTag.DROP_SCHEMA);
			}
			throw new IllegalStateException("Schema not found: " + s.schema());
		}
		final long epoch = catalog.nextEpoch();
		catalog.dropSchema(s.schema(), s.ifExists());
		final String persist = SqlDdlRender.dropSchema(s);
		catalog.appendDdl(persist);
		publishDdl(persist, epoch);
		return SqlResult.ddl(SqlStatementTag.DROP_SCHEMA);
	}

	public SqlResult setSchema(SqlSession session, SetSchemaSql s) {
		final String name = s.schema().trim().toLowerCase(Locale.ROOT);
		if (!tables.catalog().schemaExists(name)) {
			throw new IllegalArgumentException("Unknown schema: " + s.schema());
		}
		session.setCurrentSchema(name);
		return SqlResult.ddl(SqlStatementTag.SET_SCHEMA);
	}

	public SqlResult alterTable(SqlSession session, AlterTableSql s) {
		final TableCatalog catalog = tables.catalog();
		final String table = tables.resolveTable(session, s.table());
		final TableStore store = tables.requireStore(table);
		final long epoch = catalog.nextEpoch();
		final TableSchema next;
		if (s.addCheck() != null) {
			final CheckSpec check = s.addCheck();
			final String checkName = check.nameOrNull() == null || check.nameOrNull().isBlank()
					? table + CHECK_NAME_INFIX + (store.schema().checks().size() + 1)
					: check.nameOrNull();
			next = store.schema().withCheck(new CheckDef(checkName, check.expressionSql()), epoch);
		} else if (s.addColumn() != null) {
			final ColumnSpec col = s.addColumn();
			if (col.primaryKey()) {
				throw new IllegalArgumentException("ALTER ADD COLUMN PRIMARY KEY not supported");
			}
			if (applyingReplicatedDdl.get() && store.schema().column(col.name()) != null) {
				return SqlResult.ddl(SqlStatementTag.ALTER_TABLE);
			}
			next = store.schema().withColumn(
					col.name(),
					SqlType.fromToken(col.typeToken()),
					!col.notNull(),
					epoch
			);
		} else {
			next = store.schema().withoutColumn(s.dropColumn(), epoch);
		}
		store.replaceColumnSchema(next);
		catalog.replaceSchema(next);
		final String persist = SqlDdlRender.alterTable(s, table);
		catalog.appendDdl(persist);
		publishDdl(persist, epoch);
		return SqlResult.ddl(SqlStatementTag.ALTER_TABLE);
	}

	public SqlResult createView(SqlSession session, CreateViewSql s) {
		final TableCatalog catalog = tables.catalog();
		final String table = tables.resolveTable(session, s.table());
		if (catalog.getView(table) != null || catalog.exists(table)) {
			if (applyingReplicatedDdl.get()) {
				return SqlResult.ddl(SqlStatementTag.CREATE_VIEW);
			}
			throw new IllegalStateException("View or table already exists: " + table);
		}
		final long epoch = catalog.nextEpoch();
		catalog.createView(table, s.selectSql(), false);
		SqlNamedQueryExpand.clearCache();
		final String persist = SqlDdlRender.createView(table, s.selectSql());
		catalog.appendDdl(persist);
		publishDdl(persist, epoch);
		return SqlResult.ddl(SqlStatementTag.CREATE_VIEW);
	}

	public SqlResult dropView(SqlSession session, DropViewSql s) {
		final TableCatalog catalog = tables.catalog();
		final String table = tables.resolveTable(session, s.table());
		final ViewDef existing = catalog.getView(table);
		if (existing == null) {
			if (s.ifExists() || applyingReplicatedDdl.get()) {
				return SqlResult.ddl(SqlStatementTag.DROP_VIEW);
			}
			throw new IllegalStateException("View not found: " + table);
		}
		final long epoch = catalog.nextEpoch();
		catalog.dropView(table, false);
		SqlNamedQueryExpand.clearCache();
		if (existing.materialized() && catalog.exists(table)) {
			catalog.dropTable(table);
		}
		final String persist = SqlDdlRender.dropView(table, s.ifExists());
		catalog.appendDdl(persist);
		publishDdl(persist, epoch);
		return SqlResult.ddl(SqlStatementTag.DROP_VIEW);
	}

	public SqlResult createMaterializedView(SqlSession session, CreateMaterializedViewSql s) {
		if (query == null) {
			throw new IllegalStateException("query executor required for MATERIALIZED VIEW");
		}
		final TableCatalog catalog = tables.catalog();
		final String table = tables.resolveTable(session, s.table());
		if (catalog.getView(table) != null || catalog.exists(table)) {
			if (applyingReplicatedDdl.get()) {
				return SqlResult.ddl(SqlStatementTag.CREATE_MATERIALIZED_VIEW);
			}
			throw new IllegalStateException("View or table already exists: " + table);
		}
		final Stmt selectStmt = SqlStatementParser.parse(s.selectSql());
		if (!(selectStmt instanceof SelectSql select)) {
			throw new IllegalArgumentException("MATERIALIZED VIEW body must be SELECT");
		}
		if (select.hasJoins()) {
			final List<ColumnDef> joinedCols = query.joinSchemaColumns(session, select);
			final List<String> proj = select.projection();
			final boolean star = proj.size() == 1 && "*".equals(proj.getFirst());
			final List<ColumnDef> sourceCols = new ArrayList<>();
			if (star) {
				sourceCols.addAll(joinedCols);
			} else {
				for (String col : proj) {
					ColumnDef found = null;
					for (ColumnDef c : joinedCols) {
						if (c.name().equalsIgnoreCase(col)) {
							found = c;
							break;
						}
					}
					if (found == null) {
						throw new IllegalArgumentException("unknown column in MATERIALIZED VIEW: " + col);
					}
					sourceCols.add(found);
				}
			}
			if (sourceCols.isEmpty()) {
				throw new IllegalArgumentException("MATERIALIZED VIEW projection empty");
			}
			final TableSchema.Builder b = TableSchema.builder(table).schemaEpoch(catalog.nextEpoch());
			for (int i = 0; i < sourceCols.size(); i++) {
				final ColumnDef col = sourceCols.get(i);
				if (i == 0) {
					b.primaryKey(col.name(), col.type());
				} else {
					b.column(col.name(), col.type(), col.nullable());
				}
			}
			final TableSchema schema = catalog.createTable(b.build());
			tables.ensureStore(schema);
			populateMaterialized(session, table, select);
			final long epoch = schema.schemaEpoch();
			catalog.createView(table, s.selectSql(), true, epoch, epoch);
			final String persist = SqlDdlRender.createMaterializedView(table, s.selectSql());
			catalog.appendDdl(persist);
			publishDdl(persist, epoch);
			return SqlResult.ddl(SqlStatementTag.CREATE_MATERIALIZED_VIEW);
		}
		final String baseName = tables.resolveTable(session, select.table());
		final TableSchema base = catalog.requireSchema(baseName);
		final List<String> proj = select.projection();
		final boolean star = proj.size() == 1 && "*".equals(proj.getFirst());
		final List<ColumnDef> sourceCols = new ArrayList<>();
		if (star) {
			sourceCols.addAll(base.columns());
		} else {
			for (String col : proj) {
				sourceCols.add(base.requireColumn(col));
			}
		}
		if (sourceCols.isEmpty()) {
			throw new IllegalArgumentException("MATERIALIZED VIEW projection empty");
		}
		final TableSchema.Builder b = TableSchema.builder(table).schemaEpoch(catalog.nextEpoch());
		for (int i = 0; i < sourceCols.size(); i++) {
			final ColumnDef col = sourceCols.get(i);
			if (i == 0) {
				b.primaryKey(col.name(), col.type());
			} else {
				b.column(col.name(), col.type(), col.nullable());
			}
		}
		final TableSchema schema = catalog.createTable(b.build());
		tables.ensureStore(schema);
		populateMaterialized(session, table, select);
		final long epoch = schema.schemaEpoch();
		catalog.createView(table, s.selectSql(), true, epoch, epoch);
		final String persist = SqlDdlRender.createMaterializedView(table, s.selectSql());
		catalog.appendDdl(persist);
		publishDdl(persist, epoch);
		return SqlResult.ddl(SqlStatementTag.CREATE_MATERIALIZED_VIEW);
	}

	public SqlResult refreshMaterializedView(SqlSession session, RefreshMaterializedViewSql s) {
		if (query == null) {
			throw new IllegalStateException("query executor required for REFRESH MATERIALIZED VIEW");
		}
		final TableCatalog catalog = tables.catalog();
		final String table = tables.resolveTable(session, s.table());
		final ViewDef def = catalog.getView(table);
		if (def == null || !def.materialized()) {
			throw new IllegalStateException("Materialized view not found: " + table);
		}
		final Stmt selectStmt = SqlStatementParser.parse(def.selectSql());
		if (!(selectStmt instanceof SelectSql select)) {
			throw new IllegalArgumentException("MATERIALIZED VIEW body must be SELECT");
		}
		final TableStore store = tables.requireStore(table);
		final List<Object[]> existing = store.allRows();
		final ColumnDef pk = store.schema().pkColumn();
		for (Object[] row : existing) {
			store.removeIndexed(row[pk.ordinal()]);
		}
		populateMaterialized(session, table, select);
		final long epoch = catalog.nextEpoch();
		catalog.noteViewRefreshed(table, epoch, catalog.currentEpoch());
		final String persist = SqlDdlRender.refreshMaterializedView(table);
		catalog.appendDdl(persist);
		publishDdl(persist, epoch);
		return SqlResult.ddl(SqlStatementTag.REFRESH_MATERIALIZED_VIEW);
	}

	private void populateMaterialized(SqlSession session, String table, SelectSql select) {
		final SqlResult data = query.select(session, select);
		final TableStore store = tables.requireStore(table);
		for (Object[] row : data.rows()) {
			store.putIndexed(row);
		}
	}

	private void publishDdl(String sql, long schemaEpoch) {
		tables.catalog().noteAppliedDdlEpoch(schemaEpoch);
		if (applyingReplicatedDdl.get()) {
			return;
		}
		final ReplicationCoordinator repl = tables.replication();
		if (repl == null || !repl.isEnabled()) {
			return;
		}
		repl.recordDdl(sql, schemaEpoch);
	}

	public SqlResult createTrigger(SqlSession session, CreateTriggerSql s) {
		final TableCatalog catalog = tables.catalog();
		final String table = tables.resolveTable(session, s.table());
		if (catalog.getTrigger(s.name()) != null) {
			if (applyingReplicatedDdl.get()) {
				return SqlResult.ddl(SqlStatementTag.CREATE_TRIGGER);
			}
			throw new IllegalStateException("Trigger already exists: " + s.name());
		}
		final TriggerTiming timing = TriggerTiming.valueOf(s.timingToken().toUpperCase(Locale.ROOT));
		final TriggerEvent event = TriggerEvent.valueOf(s.eventToken().toUpperCase(Locale.ROOT));
		final TriggerGranularity granularity =
				TriggerGranularity.valueOf(s.granularityToken().toUpperCase(Locale.ROOT));
		final TriggerDef def = SqlTriggerBodyCompiler.compile(
				s.name(), table, timing, event, granularity, s.whenSqlOrNull(), s.bodySql());
		final long epoch = catalog.nextEpoch();
		catalog.createTrigger(def);
		final String persist = SqlDdlRender.createTrigger(
				s.name(), s.timingToken(), s.eventToken(), s.granularityToken(),
				table, s.whenSqlOrNull(), s.bodySql());
		catalog.appendDdl(persist);
		publishDdl(persist, epoch);
		return SqlResult.ddl(SqlStatementTag.CREATE_TRIGGER);
	}

	public SqlResult dropTrigger(SqlSession session, DropTriggerSql s) {
		final TableCatalog catalog = tables.catalog();
		final String tableOrNull = s.tableOrNull() == null
				? null
				: tables.resolveTable(session, s.tableOrNull());
		if (catalog.getTrigger(s.name()) == null) {
			if (s.ifExists() || applyingReplicatedDdl.get()) {
				return SqlResult.ddl(SqlStatementTag.DROP_TRIGGER);
			}
			throw new IllegalStateException("Trigger not found: " + s.name());
		}
		final long epoch = catalog.nextEpoch();
		catalog.dropTrigger(s.name(), s.ifExists(), tableOrNull);
		final String persist = SqlDdlRender.dropTrigger(s.name(), s.ifExists(), tableOrNull);
		catalog.appendDdl(persist);
		publishDdl(persist, epoch);
		return SqlResult.ddl(SqlStatementTag.DROP_TRIGGER);
	}
}