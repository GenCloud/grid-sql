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

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * K5: olympiad near-rank / neighbor pattern via WITH + LAG/LEAD (CTE-only canon).
 * <p>
 * Window ORDER BY is ASC in v1 ({@link org.genfork.grid.sql.exec.WindowOperator}).
 * LAG = lower-score neighbor; LEAD = higher-score neighbor when ordered by score.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlCteNearRankNeighborIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE olympiad (id INT PRIMARY KEY, score INT)");
		engine.execute("INSERT INTO olympiad VALUES (1, 100)");
		engine.execute("INSERT INTO olympiad VALUES (2, 80)");
		engine.execute("INSERT INTO olympiad VALUES (3, 90)");
	}

	@Test
	void withLagExpressesLowerScoreNeighbor() {
		final SqlResult r = engine.execute(
				"WITH nbr AS ("
						+ " SELECT id, score, LAG(id) OVER (ORDER BY score)"
						+ " FROM olympiad"
						+ ") SELECT id, score FROM nbr");
		assertEquals(3, r.rows().size());
		assertEquals(3, r.columns().size());
		Object lagOfLowest = null;
		Object lagOfMid = null;
		Object lagOfTop = null;
		for (Object[] row : r.rows()) {
			final int id = ((Number) row[0]).intValue();
			if (id == 2) {
				lagOfLowest = row[2];
			}
			if (id == 3) {
				lagOfMid = row[2];
			}
			if (id == 1) {
				lagOfTop = row[2];
			}
		}
		assertNull(lagOfLowest);
		assertEquals(2, ((Number) lagOfMid).intValue());
		assertEquals(3, ((Number) lagOfTop).intValue());
	}

	@Test
	void withLeadExpressesHigherScoreNeighbor() {
		final SqlResult r = engine.execute(
				"WITH nbr AS ("
						+ " SELECT id, LEAD(id) OVER (ORDER BY score)"
						+ " FROM olympiad"
						+ ") SELECT id FROM nbr");
		Object leadOfLowest = null;
		Object leadOfTop = null;
		for (Object[] row : r.rows()) {
			if (Objects.equals(row[0], 2)) {
				leadOfLowest = row[1];
			}
			if (Objects.equals(row[0], 1)) {
				leadOfTop = row[1];
			}
		}
		assertEquals(3, ((Number) leadOfLowest).intValue());
		assertNull(leadOfTop);
		assertNotNull(leadOfLowest);
	}
}