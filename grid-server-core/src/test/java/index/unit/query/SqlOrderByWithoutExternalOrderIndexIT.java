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

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ORDER BY on PK columns without external-order ArrayIndexType must still sort.
 * <p>
 * AlwaysTrue + ascending PK-prefix ORDER BY uses ordered PK leaf (not HashSet
 * {@code searchAll}); non-PK ORDER BY uses FilterThenSort wire compare.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
public class SqlOrderByWithoutExternalOrderIndexIT {
	private static final String TABLE = "order_no_ext";
	private static final int REPEAT = 40;

	@TempDir
	Path tempDir;

	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(tempDir.resolve("cat")), null, 4);
		engine.execute(
				"CREATE TABLE " + TABLE
						+ " (server_id INT, biset_type VARCHAR, PRIMARY KEY (server_id, biset_type))");
		engine.execute("INSERT INTO " + TABLE + " VALUES (125, 'ITEMS')");
		engine.execute("INSERT INTO " + TABLE + " VALUES (3, 'OTHER')");
		engine.execute("INSERT INTO " + TABLE + " VALUES (3, 'ITEMS')");
	}

	@AfterEach
	void tearDown() {
		engine = null;
	}

	@Test
	void unlimitedOrderByIsDeterministicWithoutExternalOrderIndex() {
		final String sql = "SELECT server_id, biset_type FROM " + TABLE
				+ " ORDER BY server_id, biset_type";
		for (int i = 0; i < REPEAT; i++) {
			final SqlResult rs = engine.execute(sql);
			assertEquals(3, rs.rows().size(), "run " + i);
			assertEquals(3, ((Number) rs.rows().get(0)[0]).intValue(), "run " + i + " first server_id");
			assertEquals("ITEMS", rs.rows().get(0)[1], "run " + i + " first type");
			assertEquals(3, ((Number) rs.rows().get(1)[0]).intValue(), "run " + i);
			assertEquals("OTHER", rs.rows().get(1)[1], "run " + i);
			assertEquals(125, ((Number) rs.rows().get(2)[0]).intValue(), "run " + i);
		}
	}

	@Test
	void aliasedFromOrderByIsDeterministic() {
		final String sql = "SELECT server_id, biset_type FROM " + TABLE + " T"
				+ " ORDER BY server_id, biset_type";
		for (int i = 0; i < REPEAT; i++) {
			final SqlResult rs = engine.execute(sql);
			assertEquals(3, ((Number) rs.rows().getFirst()[0]).intValue(), "run " + i);
		}
	}
}
