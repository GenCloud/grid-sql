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
package index.sql;

import java.time.ZoneOffset;

import org.genfork.grid.sql.SqlBindParam;
import org.genfork.grid.sql.SqlPreparedBinder;
import org.genfork.grid.sql.SqlStatementParser;
import org.genfork.grid.sql.ast.DmlAst.DeleteSql;
import org.genfork.grid.sql.ast.DmlAst.UpdateSql;
import org.genfork.grid.sql.ast.Stmt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bound UPDATE/DELETE keep wire {@code byte[]} in SET and materialize WHERE without full reparse hacks.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class SqlPreparedBinderBoundTest {
	private static final byte[] PAYLOAD = new byte[]{1, 2, 3, 4};

	@Test
	void updateKeepsByteaSetBindAndMaterializesWhere() {
		final String sql = "UPDATE bin_t SET payload = ? WHERE id = ?";
		final Stmt template = SqlStatementParser.parsePreparedBody(sql, ZoneOffset.UTC);
		final UpdateSql prepared = assertInstanceOf(UpdateSql.class, template);
		// JDBC / wire order: SET ? is index 0, WHERE ? is index 1.
		assertEquals(0, ((SqlBindParam) prepared.setLiterals().get("payload")).index());
		assertEquals(1, ((SqlBindParam) prepared.pkValueOrNull()).index());
		final Stmt bound = SqlPreparedBinder.bind(
				template, sql, new Object[]{PAYLOAD, 10}, ZoneOffset.UTC);
		final UpdateSql update = assertInstanceOf(UpdateSql.class, bound);
		assertTrue(update.setLiterals().get("payload") instanceof byte[]);
		assertArrayEquals(PAYLOAD, (byte[]) update.setLiterals().get("payload"));
		assertEquals(10, ((Number) update.pkValueOrNull()).intValue());
		assertFalse(update.whereSql().contains("?"), update.whereSql());
		assertTrue(update.whereSql().contains("10"), update.whereSql());
	}

	@Test
	void deleteResolvesPkAndMaterializesWhere() {
		final String sql = "DELETE FROM bin_t WHERE id = ?";
		final Stmt template = SqlStatementParser.parsePreparedBody(sql, ZoneOffset.UTC);
		final Stmt bound = SqlPreparedBinder.bind(
				template, sql, new Object[]{7}, ZoneOffset.UTC);
		final DeleteSql delete = assertInstanceOf(DeleteSql.class, bound);
		assertEquals(7, ((Number) delete.pkValueOrNull()).intValue());
		assertFalse(delete.whereSql().contains("?"), delete.whereSql());
	}
}
