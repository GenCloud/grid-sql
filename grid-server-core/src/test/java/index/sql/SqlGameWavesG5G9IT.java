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

import org.genfork.grid.catalog.TableAnalyzeStats;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused integration coverage for game-service waves G6-G9.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public class SqlGameWavesG5G9IT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void namedWindowMatchesInlineWindow() {
		engine.execute("CREATE TABLE win_g6 (id INT PRIMARY KEY, bucket INT)");
		engine.execute("INSERT INTO win_g6 VALUES (1, 1), (2, 1), (3, 2)");
		final SqlResult inline = engine.execute(
				"SELECT id, ROW_NUMBER() OVER (PARTITION BY bucket ORDER BY id) FROM win_g6");
		final SqlResult named = engine.execute(
				"SELECT id, ROW_NUMBER() OVER w FROM win_g6 WINDOW w AS (PARTITION BY bucket ORDER BY id)");
		assertEquals(inline.rows().size(), named.rows().size());
		for (int i = 0; i < inline.rows().size(); i++) {
			assertArrayEquals(inline.rows().get(i), named.rows().get(i));
		}
	}

	@Test
	void skipLockedReturnsAnotherRow() {
		engine.execute("CREATE TABLE queue_g7 (id INT PRIMARY KEY, state VARCHAR)");
		engine.execute("CREATE INDEX queue_g7_state ON queue_g7 (state)");
		engine.execute("INSERT INTO queue_g7 VALUES (1, 'new'), (2, 'new')");
		final SqlSession first = engine.newSession();
		final SqlSession second = engine.newSession();
		engine.execute(first, "BEGIN");
		engine.execute(second, "BEGIN");
		try {
			final Object firstId = engine.execute(first,
					"SELECT id FROM queue_g7 WHERE state = 'new' LIMIT 1 FOR UPDATE").rows().getFirst()[0];
			final Object secondId = engine.execute(second,
					"SELECT id FROM queue_g7 WHERE state = 'new' LIMIT 1 FOR UPDATE SKIP LOCKED").rows().getFirst()[0];
			assertNotEquals(firstId, secondId);
		} finally {
			engine.execute(first, "ROLLBACK");
			engine.execute(second, "ROLLBACK");
		}
	}

	@Test
	void analyzeBuildsAndRoundTripsFrequencyHistograms() {
		engine.execute("CREATE TABLE hist_g8 (id INT PRIMARY KEY, flag INT)");
		engine.execute("INSERT INTO hist_g8 VALUES (1, 7), (2, 7), (3, 9)");
		engine.execute("ANALYZE hist_g8");
		final TableAnalyzeStats stats = engine.catalog().getAnalyzeStats("hist_g8");
		assertTrue(stats.columnHistograms().containsKey("flag"));
		final TableAnalyzeStats parsed = TableAnalyzeStats.parse(stats.toFileBody());
		assertEquals(stats.columnHistograms().get("flag").size(), parsed.columnHistograms().get("flag").size());
		final String explain = String.valueOf(
				engine.execute("EXPLAIN SELECT id FROM hist_g8 WHERE flag = 7").rows().getFirst()[2]);
		assertTrue(explain.contains("histogram=true"), explain);
		assertTrue(explain.contains("histogramEstRows=2"), explain);
	}

	@Test
	void checkRejectsInsertAndUpdateBeforeVisibility() {
		engine.execute("CREATE TABLE check_g9 (id INT PRIMARY KEY, score INT, CONSTRAINT score_positive CHECK (score >= 0 AND score <= 100))");
		engine.execute("INSERT INTO check_g9 VALUES (1, 10)");
		assertThrows(IllegalStateException.class,
				() -> engine.execute("INSERT INTO check_g9 VALUES (2, 101)"));
		assertThrows(IllegalStateException.class,
				() -> engine.execute("UPDATE check_g9 SET score = 101 WHERE id = 1"));
		assertEquals(10, engine.execute("SELECT score FROM check_g9 WHERE id = 1").rows().getFirst()[0]);
		engine.execute("ALTER TABLE check_g9 ADD CHECK (score != 50)");
		assertThrows(IllegalStateException.class,
				() -> engine.execute("INSERT INTO check_g9 VALUES (3, 50)"));
	}
}