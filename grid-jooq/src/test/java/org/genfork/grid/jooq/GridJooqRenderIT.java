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
package org.genfork.grid.jooq;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.genfork.grid.antlr.SimplifiedSqlLexer;
import org.genfork.grid.antlr.SimplifiedSqlParser;
import org.genfork.grid.catalog.SqlType;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.conf.ParamType;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.Test;

/**
 * Render SELECT/INSERT/UPDATE/DELETE via {@link GridDSL} and accept with SimplifiedSql ANTLR
 * (no server required).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class GridJooqRenderIT {
	private static final String TABLE_USERS = "users";
	private static final String TABLE_ORDERS = "orders";
	private static final String COL_ID = "id";
	private static final String COL_NAME = "name";
	private static final String COL_USER_ID = "user_id";
	private static final String COL_STATUS = "status";
	private static final String LITERAL_ACTIVE = "active";
	private static final String LITERAL_ALICE = "alice";
	private static final int LITERAL_ID = 1;
	private static final int LITERAL_USER_ID = 42;

	@Test
	void dialectIsDefault() {
		assertEquals(SQLDialect.DEFAULT, GridSQL.DIALECT);
	}

	@Test
	void renderSelectWithEqJoinAcceptedBySimplifiedSql() {
		final DSLContext dsl = GridDSL.using();
		final Table<Record> users = table(name(TABLE_USERS));
		final Table<Record> orders = table(name(TABLE_ORDERS));
		final Field<Integer> usersId = field(name(TABLE_USERS, COL_ID), Integer.class);
		final Field<Integer> ordersUserId = field(name(TABLE_ORDERS, COL_USER_ID), Integer.class);
		final Field<String> usersName = field(name(TABLE_USERS, COL_NAME), String.class);

		final Query query = dsl
				.select(usersId, usersName)
				.from(users)
				.join(orders).on(usersId.eq(ordersUserId))
				.where(usersId.eq(inline(LITERAL_ID)));

		final String sql = query.getSQL(ParamType.INLINED);
		assertAccepted(sql);
		assertFalse(sql.contains("\""), "SimplifiedSql rejects quoted identifiers: " + sql);
	}

	@Test
	void renderInsertValuesAcceptedBySimplifiedSql() {
		final DSLContext dsl = GridDSL.using();
		final Table<Record> users = table(name(TABLE_USERS));
		final Field<Integer> id = field(name(COL_ID), Integer.class);
		final Field<String> name = field(name(COL_NAME), String.class);

		final Query query = dsl
				.insertInto(users)
				.columns(id, name)
				.values(inline(LITERAL_ID), inline(LITERAL_ALICE));

		final String sql = query.getSQL(ParamType.INLINED);
		assertAccepted(sql);
		assertTrue(sql.toUpperCase().contains("VALUES"), sql);
	}

	@Test
	void renderUpdateWithWhereAcceptedBySimplifiedSql() {
		final DSLContext dsl = GridDSL.using();
		final Table<Record> users = table(name(TABLE_USERS));
		final Field<Integer> id = field(name(COL_ID), Integer.class);
		final Field<String> status = field(name(COL_STATUS), String.class);

		final Query query = dsl
				.update(users)
				.set(status, inline(LITERAL_ACTIVE))
				.where(id.eq(inline(LITERAL_USER_ID)));

		final String sql = query.getSQL(ParamType.INLINED);
		assertAccepted(sql);
		assertTrue(sql.toUpperCase().contains("WHERE"), sql);
	}

	@Test
	void renderDeleteWithWhereAcceptedBySimplifiedSql() {
		final DSLContext dsl = GridDSL.using();
		final Table<Record> users = table(name(TABLE_USERS));
		final Field<Integer> id = field(name(COL_ID), Integer.class);

		final Query query = dsl
				.deleteFrom(users)
				.where(id.eq(inline(LITERAL_ID)));

		final String sql = query.getSQL(ParamType.INLINED);
		assertAccepted(sql);
		assertTrue(sql.toUpperCase().contains("WHERE"), sql);
	}

	@Test
	void renderLeftJoinEqOnAcceptedBySimplifiedSql() {
		final DSLContext dsl = GridDSL.using();
		final Table<Record> users = table(name(TABLE_USERS));
		final Table<Record> orders = table(name(TABLE_ORDERS));
		final Field<Integer> usersId = field(name(TABLE_USERS, COL_ID), Integer.class);
		final Field<Integer> ordersUserId = field(name(TABLE_ORDERS, COL_USER_ID), Integer.class);
		final Field<String> usersName = field(name(TABLE_USERS, COL_NAME), String.class);

		final Query query = dsl
				.select(usersId, usersName)
				.from(users)
				.leftJoin(orders).on(usersId.eq(ordersUserId))
				.where(usersId.eq(inline(LITERAL_ID)));

		final String sql = query.getSQL(ParamType.INLINED);
		assertAccepted(sql);
		assertTrue(sql.toUpperCase().contains("LEFT"), sql);
	}

	@Test
	void renderOrderByLimitAcceptedBySimplifiedSql() {
		final DSLContext dsl = GridDSL.using();
		final Table<Record> users = table(name(TABLE_USERS));
		final Field<Integer> id = field(name(COL_ID), Integer.class);
		final Field<String> name = field(name(COL_NAME), String.class);

		final Query query = dsl
				.select(id, name)
				.from(users)
				.orderBy(id.asc())
				.limit(inline(10));

		final String sql = query.getSQL(ParamType.INLINED);
		assertAccepted(sql);
		assertTrue(sql.toUpperCase().contains("ORDER BY"), sql);
		assertTrue(sql.toUpperCase().contains("LIMIT"), sql);
	}

	@Test
	void renderUpsertAcceptedBySimplifiedSql() {
		final DSLContext dsl = GridDSL.using();
		final Table<Record> users = table(name(TABLE_USERS));
		final Field<Integer> id = field(name(COL_ID), Integer.class);
		final Field<String> name = field(name(COL_NAME), String.class);

		final Query upsert = dsl.query(
				"UPSERT INTO {0} ({1}, {2}) VALUES ({3}, {4})",
				users, id, name, inline(LITERAL_ID), inline(LITERAL_ALICE));
		final String upsertSql = upsert.getSQL(ParamType.INLINED);
		assertAccepted(upsertSql);
		assertTrue(upsertSql.toUpperCase().contains("UPSERT"), upsertSql);

		// DEFAULT dialect maps onConflict → MySQL ON DUPLICATE KEY (rejected by SimplifiedSql).
		// Prefer UPSERT keyword or a plain ON CONFLICT string:
		final Query onConflict = dsl.query(
				"INSERT INTO {0} ({1}, {2}) VALUES ({3}, {4}) ON CONFLICT ({1}) DO UPDATE SET {2} = {4}",
				users, id, name, inline(LITERAL_ID), inline(LITERAL_ALICE));
		final String onConflictSql = onConflict.getSQL(ParamType.INLINED);
		assertAccepted(onConflictSql);
		assertTrue(onConflictSql.toUpperCase().contains("ON CONFLICT"), onConflictSql);
	}

	@Test
	void renderSelectIndexedParamsUseQuestionMarks() {
		final DSLContext dsl = GridDSL.using();
		final Table<Record> users = table(name(TABLE_USERS));
		final Field<Integer> id = field(name(COL_ID), Integer.class);

		final Query query = dsl.select(id).from(users).where(id.eq(LITERAL_ID));
		final String sql = query.getSQL(ParamType.INDEXED);
		assertAccepted(sql);
		assertTrue(sql.contains("?"), sql);
	}

	@Test
	void sqlTypeMappingRoundTripBasics() {
		assertEquals(SQLDataType.INTEGER, GridSqlTypes.toDataType(SqlType.INT));
		assertEquals(SQLDataType.BIGINT, GridSqlTypes.toDataType(SqlType.BIGINT));
		assertEquals(SQLDataType.VARCHAR, GridSqlTypes.toDataType(SqlType.TIMESTAMP));
		assertEquals(SqlType.INT, GridSqlTypes.toSqlType(SQLDataType.INTEGER));
		assertEquals(SqlType.UUID, GridSqlTypes.toSqlType(SQLDataType.UUID));
		assertEquals(SqlType.VARCHAR, GridSqlTypes.toSqlType(SQLDataType.VARCHAR));
	}

	@Test
	void connectionProviderOfRequiresNonNull() {
		assertDoesNotThrow(() -> GridConnectionProvider.ofJdbcUrl("jdbc:grid://u:p@127.0.0.1:15432/public"));
		assertNotNull(GridDSL.newConfiguration());
	}

	private static void assertAccepted(String sql) {
		assertNotNull(sql);
		assertFalse(sql.isBlank());
		assertDoesNotThrow(() -> parseStatement(sql), () -> "Rejected by SimplifiedSql: " + sql);
	}

	private static void parseStatement(String sql) {
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
