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
package org.genfork.grid.sql.util;

import org.genfork.grid.catalog.CatalogPersistUtil;
import org.genfork.grid.catalog.CatalogQualifiedName;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlStatementParser;
import org.genfork.grid.sql.SqlStatementTag;
import org.genfork.grid.sql.SqlStatementTagger;
import org.genfork.grid.sql.ast.DdlAst.CreateIndexSql;
import org.genfork.grid.sql.ast.DdlAst.CreateMaterializedViewSql;
import org.genfork.grid.sql.ast.DdlAst.CreateSchemaSql;
import org.genfork.grid.sql.ast.DdlAst.CreateSequenceSql;
import org.genfork.grid.sql.ast.DdlAst.CreateTableSql;
import org.genfork.grid.sql.ast.DdlAst.CreateTriggerSql;
import org.genfork.grid.sql.ast.DdlAst.CreateViewSql;
import org.genfork.grid.sql.ast.DdlAst.DropIndexSql;
import org.genfork.grid.sql.ast.DdlAst.DropSchemaSql;
import org.genfork.grid.sql.ast.DdlAst.DropSequenceSql;
import org.genfork.grid.sql.ast.DdlAst.DropTableSql;
import org.genfork.grid.sql.ast.DdlAst.DropTriggerSql;
import org.genfork.grid.sql.ast.DdlAst.DropViewSql;
import org.genfork.grid.sql.ast.Stmt;

/**
 * Whether a replicated DDL statement's catalog effect is already present (duplicate-skip gate).
 * <p>
 * Epoch alone is not proof of object presence — duplicate ACK without effect leaves VIEW/TABLE holes.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class CatalogDdlEffectPresentUtil {
	private static final String SCHEMA_PUBLIC = CatalogPersistUtil.SCHEMA_PUBLIC;

	private CatalogDdlEffectPresentUtil() {
	}

	/**
	 * {@code true} when skipping execute is safe because the statement's primary effect is already live.
	 * Non-CREATE / unknown → {@code false} (must not silent-skip; caller keeps stale reject / execute path).
	 */
	public static boolean effectPresent(TableCatalog catalog, String ddlSql) {
		if (catalog == null || ddlSql == null || ddlSql.isBlank()) {
			return false;
		}
		final Stmt stmt;
		try {
			stmt = SqlStatementParser.parse(ddlSql.trim());
		} catch (RuntimeException ex) {
			return false;
		}
		final SqlStatementTag tag = SqlStatementTagger.tagOf(stmt);
		return switch (tag) {
			case CREATE_TABLE -> createTablePresent(catalog, (CreateTableSql) stmt);
			case CREATE_VIEW -> createViewPresent(catalog, (CreateViewSql) stmt);
			case CREATE_MATERIALIZED_VIEW -> createMatViewPresent(catalog, (CreateMaterializedViewSql) stmt);
			case CREATE_SCHEMA -> createSchemaPresent(catalog, (CreateSchemaSql) stmt);
			case CREATE_SEQUENCE -> createSequencePresent(catalog, (CreateSequenceSql) stmt);
			case CREATE_INDEX -> createIndexPresent(catalog, (CreateIndexSql) stmt);
			case CREATE_TRIGGER -> createTriggerPresent(catalog, (CreateTriggerSql) stmt);
			case DROP_TABLE -> dropTableAbsent(catalog, (DropTableSql) stmt);
			case DROP_VIEW -> dropViewAbsent(catalog, (DropViewSql) stmt);
			case DROP_SCHEMA -> dropSchemaAbsent(catalog, (DropSchemaSql) stmt);
			case DROP_SEQUENCE -> dropSequenceAbsent(catalog, (DropSequenceSql) stmt);
			case DROP_INDEX -> dropIndexAbsent(catalog, (DropIndexSql) stmt);
			case DROP_TRIGGER -> dropTriggerAbsent(catalog, (DropTriggerSql) stmt);
			default -> false;
		};
	}

	/**
	 * {@code true} when this DDL is a CREATE* that may heal a missing object under duplicate epoch.
	 */
	public static boolean isCreatelikeHealCandidate(String ddlSql) {
		if (ddlSql == null || ddlSql.isBlank()) {
			return false;
		}
		try {
			final Stmt stmt = SqlStatementParser.parse(ddlSql.trim());
			final SqlStatementTag tag = SqlStatementTagger.tagOf(stmt);
			return tag == SqlStatementTag.CREATE_TABLE
					|| tag == SqlStatementTag.CREATE_VIEW
					|| tag == SqlStatementTag.CREATE_MATERIALIZED_VIEW
					|| tag == SqlStatementTag.CREATE_SCHEMA
					|| tag == SqlStatementTag.CREATE_SEQUENCE
					|| tag == SqlStatementTag.CREATE_INDEX
					|| tag == SqlStatementTag.CREATE_TRIGGER;
		} catch (RuntimeException ex) {
			return false;
		}
	}

	private static boolean createTablePresent(TableCatalog catalog, CreateTableSql s) {
		return catalog.exists(catalogKey(s.table()));
	}

	private static boolean createViewPresent(TableCatalog catalog, CreateViewSql s) {
		return catalog.getView(catalogKey(s.table())) != null;
	}

	private static boolean createMatViewPresent(TableCatalog catalog, CreateMaterializedViewSql s) {
		final String key = catalogKey(s.table());
		return catalog.getView(key) != null || catalog.exists(key);
	}

	private static boolean createSchemaPresent(TableCatalog catalog, CreateSchemaSql s) {
		return catalog.schemaExists(s.schema());
	}

	private static boolean createSequencePresent(TableCatalog catalog, CreateSequenceSql s) {
		return catalog.getSequence(catalogKey(s.name())) != null;
	}

	private static boolean createIndexPresent(TableCatalog catalog, CreateIndexSql s) {
		final String table = catalogKey(s.table());
		if (!catalog.exists(table)) {
			return false;
		}
		return catalog.requireSchema(table).findIndex(s.indexName()) != null;
	}

	private static boolean createTriggerPresent(TableCatalog catalog, CreateTriggerSql s) {
		return catalog.getTrigger(s.name()) != null;
	}

	private static boolean dropTableAbsent(TableCatalog catalog, DropTableSql s) {
		return !catalog.exists(catalogKey(s.table()));
	}

	private static boolean dropViewAbsent(TableCatalog catalog, DropViewSql s) {
		return catalog.getView(catalogKey(s.table())) == null;
	}

	private static boolean dropSchemaAbsent(TableCatalog catalog, DropSchemaSql s) {
		return !catalog.schemaExists(s.schema());
	}

	private static boolean dropSequenceAbsent(TableCatalog catalog, DropSequenceSql s) {
		return catalog.getSequence(catalogKey(s.name())) == null;
	}

	private static boolean dropIndexAbsent(TableCatalog catalog, DropIndexSql s) {
		if (s.tableOrNull() == null || s.tableOrNull().isBlank()) {
			for (String table : catalog.tableNames()) {
				if (catalog.requireSchema(table).findIndex(s.indexName()) != null) {
					return false;
				}
			}
			return true;
		}
		final String table = catalogKey(s.tableOrNull());
		if (!catalog.exists(table)) {
			return true;
		}
		return catalog.requireSchema(table).findIndex(s.indexName()) == null;
	}

	private static boolean dropTriggerAbsent(TableCatalog catalog, DropTriggerSql s) {
		return catalog.getTrigger(s.name()) == null;
	}

	private static String catalogKey(String ref) {
		return CatalogQualifiedName.parse(ref, SCHEMA_PUBLIC).catalogKey();
	}
}
