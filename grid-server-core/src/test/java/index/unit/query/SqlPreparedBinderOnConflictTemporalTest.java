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
package index.unit.query;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.genfork.grid.sql.SqlPreparedBinder;
import org.genfork.grid.sql.SqlStatementParser;
import org.genfork.grid.sql.ast.DmlAst.InsertSql;
import org.genfork.grid.sql.ast.Stmt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterize ON CONFLICT bind slot order for mixed INT / BOOLEAN / TIMESTAMP.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class SqlPreparedBinderOnConflictTemporalTest {
	private static final String UPSERT =
			"INSERT INTO pledge_game_season (id, season, step, prev_winner_id, prev_winner_got_item, updated_at) "
					+ "VALUES (?, ?, ?, ?, ?, ?) "
					+ "ON CONFLICT (id) DO UPDATE SET season = ?, step = ?, prev_winner_id = ?, "
					+ "prev_winner_got_item = ?, updated_at = ?";

	@Test
	void bindSlotsMatchJdbcLeftToRightOrder() {
		final LocalDateTime wall = LocalDateTime.of(2026, 10, 10, 11, 28, 8);
		final Object[] binds = new Object[]{1, 2, 3, 4, true, wall, 5, 6, 7, false, wall};
		final Stmt template = SqlStatementParser.parsePreparedBody(UPSERT, ZoneOffset.UTC);
		final Stmt bound = SqlPreparedBinder.bind(template, UPSERT, binds, ZoneOffset.UTC);
		final InsertSql insert = assertInstanceOf(InsertSql.class, bound);
		assertEquals(List.of("id", "season", "step", "prev_winner_id", "prev_winner_got_item", "updated_at"),
				insert.columns());
		final List<Object> row = insert.rows().getFirst();
		assertEquals(1, row.get(0));
		assertEquals(2, row.get(1));
		assertEquals(3, row.get(2));
		assertEquals(4, row.get(3));
		assertEquals(true, row.get(4));
		assertEquals(wall, row.get(5));
		final Map<String, Object> sets = insert.onConflictOrNull().updateSets();
		assertEquals(5, sets.get("season"));
		assertEquals(6, sets.get("step"));
		assertEquals(7, sets.get("prev_winner_id"));
		assertEquals(false, sets.get("prev_winner_got_item"));
		assertEquals(wall, sets.get("updated_at"));
		assertTrue(row.get(5) instanceof LocalDateTime);
	}
}
