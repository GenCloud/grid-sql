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

import org.genfork.grid.catalog.TableAnalyzeStats;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.query.plan.TableRowStats;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlStatementTag;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.5: SQL ANALYZE table crude stats + EXPLAIN stats source.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class SqlAnalyzeTableIT {
	@TempDir
	Path dataDir;

	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(dataDir), null, 4);
		engine.execute("CREATE TABLE an_t (id INT PRIMARY KEY, flag INT)");
		engine.execute("CREATE INDEX idx_flag ON an_t (flag)");
		engine.execute("INSERT INTO an_t VALUES (1, 1)");
		engine.execute("INSERT INTO an_t VALUES (2, 1)");
		engine.execute("INSERT INTO an_t VALUES (3, 2)");
	}

	@Test
	void analyzePersistsCrudeStatsAndExplainShowsSource() {
		final SqlResult analyzed = engine.execute("ANALYZE an_t");
		assertEquals(SqlStatementTag.ANALYZE, analyzed.statementTag());
		assertEquals(3L, analyzed.rowsAffected());

		final TableAnalyzeStats stats = engine.catalog().getAnalyzeStats("an_t");
		assertEquals(3L, stats.rowCount());
		assertTrue(stats.fanOut("flag") >= 1L);

		final TableStore store = engine.catalog().getStore("an_t");
		final TableRowStats rowStats = store.approxRowStats();
		assertEquals(TableRowStats.SOURCE_ANALYZE, rowStats.source());
		assertEquals(3L, rowStats.estimatedRows());

		final SqlResult explain = engine.execute("EXPLAIN SELECT id FROM an_t WHERE flag = 1");
		final String detail = String.valueOf(explain.rows().getFirst()[2]);
		assertTrue(detail.contains("stats=" + TableRowStats.SOURCE_ANALYZE), detail);
		assertTrue(detail.contains("estRows=3"), detail);
	}
}