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
import org.genfork.grid.sql.exec.SqlExplainKinds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * LEFT/INNER JOIN on a leading column of a composite PRIMARY KEY must match all right rows.
 * <p>
 * Right PK is {@code (p1, p2, p3)} but ON uses only {@code p1}. Map keys are the full
 * composite wire tuple — scalar PK point-lookup must not be used for this shape.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class CompositePkPartialLeftJoinIT {
	private static final String PARENT = "parent_t";
	private static final String CHILD = "child_t";
	private static final int PARENT_ID = 1;
	private static final int EXPECTED_MATCHES = 3;

	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE " + PARENT + " (id INT PRIMARY KEY, owner_id INT)");
		engine.execute("CREATE TABLE " + CHILD + " ("
				+ "p1 INT NOT NULL, "
				+ "p2 INT NOT NULL, "
				+ "p3 INT NOT NULL, "
				+ "payload INT, "
				+ "PRIMARY KEY (p1, p2, p3))");
		engine.execute("INSERT INTO " + PARENT + " VALUES (" + PARENT_ID + ", 10)");
		engine.execute("INSERT INTO " + CHILD + " VALUES (" + PARENT_ID + ", 1, 100, 0)");
		engine.execute("INSERT INTO " + CHILD + " VALUES (" + PARENT_ID + ", 1, 200, 1)");
		engine.execute("INSERT INTO " + CHILD + " VALUES (" + PARENT_ID + ", 2, 300, 0)");
	}

	@Test
	void baseTableHasThreeChildRows() {
		final SqlResult all = engine.execute(
				"SELECT p3 FROM " + CHILD + " WHERE p1 = " + PARENT_ID);
		assertEquals(EXPECTED_MATCHES, all.rows().size(),
				"prefix EQ on composite PK must see all child rows");
	}

	@Test
	void leftJoinOnLeadingCompositePkColumnReturnsAllChildRows() {
		final SqlResult joined = engine.execute(
				"SELECT " + PARENT + ".id, " + CHILD + ".p2, "
						+ CHILD + ".p3, " + CHILD + ".payload "
						+ "FROM " + PARENT + " "
						+ "LEFT JOIN " + CHILD + " ON " + CHILD + ".p1 = " + PARENT + ".id "
						+ "WHERE " + PARENT + ".id = " + PARENT_ID);

		assertEquals(EXPECTED_MATCHES, joined.rows().size(),
				"LEFT JOIN on composite PK leading column must fan out to all matching right rows");
		for (Object[] row : joined.rows()) {
			assertEquals(PARENT_ID, ((Number) row[0]).intValue());
			assertNotNull(row[1], "p2 must not be LEFT-null when child rows exist");
			assertNotNull(row[2], "p3 must not be LEFT-null when child rows exist");
		}
	}

	@Test
	void innerJoinOnLeadingCompositePkColumnReturnsAllChildRows() {
		final SqlResult joined = engine.execute(
				"SELECT " + CHILD + ".p3 FROM " + PARENT + " "
						+ "INNER JOIN " + CHILD + " ON " + CHILD + ".p1 = " + PARENT + ".id "
						+ "WHERE " + PARENT + ".id = " + PARENT_ID);
		assertEquals(EXPECTED_MATCHES, joined.rows().size(),
				"INNER JOIN must fan out to all matching right rows for partial composite PK ON");
	}

	@Test
	void explainPartialCompositeJoinIsNotJoinPk() {
		final SqlResult expl = engine.execute(
				"EXPLAIN SELECT * FROM " + PARENT + " "
						+ "LEFT JOIN " + CHILD + " ON " + CHILD + ".p1 = " + PARENT + ".id");
		assertFalse(expl.rows().isEmpty());
		final String kind = String.valueOf(expl.rows().getFirst()[0]);
		assertFalse(SqlExplainKinds.JOIN_PK.equals(kind),
				"partial composite ON must not choose JOIN_PK: kind=" + kind);
	}
}
