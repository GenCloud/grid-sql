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
package org.genfork.grid.jooq.dialect;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.genfork.grid.antlr.SimplifiedSqlLexer;
import org.genfork.grid.antlr.SimplifiedSqlParser;
import org.genfork.grid.jooq.GridDSL;
import org.genfork.grid.jooq.GridSQL;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.SQLDialect;
import org.jooq.VisitListenerProvider;
import org.jooq.conf.ParamType;
import org.jooq.conf.RenderOptionalKeyword;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Characterization: Grid dialect QueryParts render SimplifiedSql without SQLITE / strip listeners.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class GridDialectRenderIT {
	private static final String TABLE = "player_quests";
	private static final String COL_OWNER = "owner_id";
	private static final String COL_QUEST = "quest_id";
	private static final String COL_JOURNAL = "journal";
	private static final int OWNER = 42;
	private static final int QUEST = 7;
	private static final String JOURNAL = "state";

	@Test
	void onConflictDoUpdateRendersSimplifiedSqlNotDuplicateKey() {
		final DSLContext dsl = GridDSL.using();
		final Query query = GridDSL.insertInto(dsl, table(TABLE))
				.set(field(COL_OWNER), OWNER)
				.set(field(COL_QUEST), QUEST)
				.set(field(COL_JOURNAL), JOURNAL)
				.onConflict(field(COL_OWNER), field(COL_QUEST))
				.doUpdate()
				.set(field(COL_JOURNAL), JOURNAL)
				.toQuery();
		final String sql = query.getSQL(ParamType.INLINED).toUpperCase();
		assertTrue(sql.contains("ON CONFLICT ("), sql);
		assertTrue(sql.contains("DO UPDATE SET"), sql);
		assertFalse(sql.contains("ON DUPLICATE KEY"), sql);
		assertAccepted(query.getSQL(ParamType.INLINED));
	}

	@Test
	void onConflictDoUpdateWhereIsFailClosed() {
		final DSLContext dsl = GridDSL.using();
		assertThrows(UnsupportedOperationException.class, () -> GridDSL.insertInto(dsl, table(TABLE))
				.set(field(COL_OWNER), OWNER)
				.onConflict(field(COL_OWNER))
				.doUpdate()
				.set(field(COL_JOURNAL), JOURNAL)
				.where(field(COL_OWNER).eq(OWNER)));
	}

	@Test
	void onConflictDoNothingRendersDoNothing() {
		final DSLContext dsl = GridDSL.using();
		final Query query = GridDSL.insertInto(dsl, table(TABLE))
				.set(field(COL_OWNER), OWNER)
				.set(field(COL_QUEST), QUEST)
				.onConflictDoNothing(field(COL_OWNER), field(COL_QUEST))
				.toQuery();
		final String sql = query.getSQL(ParamType.INLINED).toUpperCase();
		assertTrue(sql.contains("ON CONFLICT ("), sql);
		assertTrue(sql.contains("DO NOTHING"), sql);
		assertFalse(sql.contains("ON DUPLICATE KEY"), sql);
		assertAccepted(query.getSQL(ParamType.INLINED));
	}

	@Test
	void returningIsNativeNotLastInsertRowid() {
		final DSLContext dsl = GridDSL.using();
		final Query query = GridDSL.insertReturning(dsl, table(TABLE))
				.set(field(COL_OWNER), OWNER)
				.set(field(COL_JOURNAL), JOURNAL)
				.returning(field(COL_OWNER), field(COL_JOURNAL))
				.toQuery();
		final String sql = query.getSQL(ParamType.INLINED);
		final String upper = sql.toUpperCase();
		assertTrue(upper.contains("RETURNING"), sql);
		assertFalse(sql.toLowerCase().contains("last_insert_rowid"), sql);
		assertFalse(sql.toLowerCase().contains("_rowid_"), sql);
		assertAccepted(sql);
	}

	@Test
	void truncateIsTruncateTableNotDeleteFrom() {
		final DSLContext dsl = GridDSL.using();
		final String sql = GridDSL.truncateQuery(dsl, table(TABLE)).getSQL(ParamType.INLINED).toUpperCase();
		assertTrue(sql.contains("TRUNCATE TABLE"), sql);
		assertFalse(sql.contains("DELETE FROM"), sql);
		assertAccepted(GridDSL.truncateSql(table(TABLE)));
	}

	@Test
	void installProductFailsFastOnSqlitePreset() {
		final DefaultConfiguration configuration = new DefaultConfiguration();
		configuration.set(SQLDialect.SQLITE);
		final IllegalStateException ex = assertThrows(
				IllegalStateException.class,
				() -> GridSQL.installProduct(configuration));
		assertTrue(ex.getMessage().contains("SQLITE") || ex.getMessage().contains("vendor"), ex.getMessage());
	}

	@Test
	void installProductOverwriteForcesDefault() {
		final DefaultConfiguration configuration = new DefaultConfiguration();
		configuration.set(SQLDialect.SQLITE);
		GridSQL.installProductOverwrite(configuration);
		assertEquals(SQLDialect.DEFAULT, configuration.dialect());
	}

	@Test
	void installProductRegistersNoVisitListeners() {
		final DefaultConfiguration configuration = new DefaultConfiguration();
		GridSQL.installProduct(configuration);
		final VisitListenerProvider[] providers = configuration.visitListenerProviders();
		assertTrue(providers == null || providers.length == 0);
	}

	@Test
	void forUpdateSkipLockedRendersUnderProductInstall() {
		final DSLContext dsl = GridDSL.using();
		final Query query = dsl.select(field(COL_OWNER))
				.from(table(TABLE))
				.forUpdate()
				.skipLocked();
		final String sql = query.getSQL(ParamType.INLINED).toUpperCase();
		assertTrue(sql.contains("FOR UPDATE"), sql);
		assertTrue(sql.contains("SKIP LOCKED"), sql);
		assertEquals(GridForUpdateSkipLocked.SQL, "FOR UPDATE SKIP LOCKED");
	}

	@Test
	void logicalDialectCapabilities() {
		assertTrue(GridSQL.logicalDialect().supportsOnConflictDoUpdate());
		assertTrue(GridSQL.logicalDialect().supportsTruncate());
		assertDoesNotThrow(() -> GridDialect.INSTANCE.name());
	}

	@Test
	void settingsForceAsKeywordOnFieldAliases() {
		assertEquals(
				RenderOptionalKeyword.ON,
				GridSQL.settings().getRenderOptionalAsKeywordForFieldAliases());
	}

	/**
	 * jOOQ {@code fetchExists} / {@code selectOne().as("one")} must render {@code AS one}
	 * (SimplifiedSql rejects bare {@code SELECT 1 one}).
	 */
	@Test
	void fetchExistsShapeRendersAsOneAcceptedBySimplifiedSql() {
		final DSLContext dsl = GridDSL.using();
		final Query query = dsl.select(DSL.exists(
				dsl.select(DSL.one().as("one"))
						.from(table(TABLE))
						.where(field(COL_OWNER).eq(OWNER))));
		final String sql = query.getSQL(ParamType.INLINED);
		final String upper = sql.toUpperCase();
		assertTrue(upper.contains("EXISTS"), sql);
		assertTrue(upper.contains("AS ONE") || upper.contains("AS \"ONE\""), sql);
		assertFalse(sql.matches("(?i).*SELECT\\s+1\\s+one\\s+FROM.*"), sql);
		assertAccepted(sql);
	}

	private static void assertAccepted(String sql) {
		final SimplifiedSqlLexer lexer = new SimplifiedSqlLexer(CharStreams.fromString(sql.trim()));
		lexer.removeErrorListeners();
		final CommonTokenStream tokens = new CommonTokenStream(lexer);
		final SimplifiedSqlParser parser = new SimplifiedSqlParser(tokens);
		parser.removeErrorListeners();
		parser.setErrorHandler(new BailErrorStrategy());
		try {
			parser.statement();
		} catch (ParseCancellationException e) {
			throw new IllegalArgumentException("SimplifiedSql reject: " + sql, e);
		}
		if (parser.getNumberOfSyntaxErrors() > 0) {
			throw new IllegalArgumentException("SimplifiedSql syntax errors: " + sql);
		}
	}
}
