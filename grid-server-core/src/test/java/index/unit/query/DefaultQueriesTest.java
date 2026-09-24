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

import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.query.plan.ExplainQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Filter / sort / limit coverage on SQL-first {@link TableStore}.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class DefaultQueriesTest extends AbstractQueriesTest {
	private static final String TABLE = "query_entity";

	private static final int COL_ID = 0;
	private static final int COL_NAME = 1;
	private static final int COL_AGE = 2;
	private static final int COL_SALARY = 3;
	private static final int COL_ACTIVE = 4;
	private static final int COL_DESCRIPTION = 5;

	private String firstDescription;

	@Override
	protected TableSchema buildSchema() {
		return TableSchema.builder(TABLE)
				.primaryKey("id", SqlType.VARCHAR)
				.column("name", SqlType.VARCHAR)
				.column("age", SqlType.INT)
				.column("salary", SqlType.DOUBLE)
				.column("active", SqlType.BOOLEAN)
				.column("description", SqlType.VARCHAR)
				.externalOrder("name")
				.externalOrder("age")
				.externalOrder("salary")
				.externalOrder("description")
				.index(IndexDef.of("idx_age", IndexType.LAX, "age"))
				.index(IndexDef.of("idx_salary", IndexType.LAX, "salary"))
				.index(IndexDef.of("idx_name", IndexType.LAX, "name"))
				.build();
	}

	@Override
	protected int fillCount() {
		return 15000;
	}

	@Override
	protected void fillRows() {
		for (int i = 0; i < fillCount(); i++) {
			final String name = "Name-" + ThreadLocalRandom.current().nextInt(100);
			final int age = ThreadLocalRandom.current().nextInt(100);
			final double salary = 1000 + ThreadLocalRandom.current().nextDouble() * 4000;
			final boolean active = ThreadLocalRandom.current().nextBoolean();
			final String description = "Description-" + ThreadLocalRandom.current().nextInt(1000);
			if (firstDescription == null) {
				firstDescription = description;
			}
			store.putIndexed("id-" + i, name, age, salary, active, description);
		}
	}

	private static String nameOf(Object[] row) {
		return (String) row[COL_NAME];
	}

	private static int ageOf(Object[] row) {
		return ((Number) row[COL_AGE]).intValue();
	}

	private static double salaryOf(Object[] row) {
		return ((Number) row[COL_SALARY]).doubleValue();
	}

	private static boolean activeOf(Object[] row) {
		return (Boolean) row[COL_ACTIVE];
	}

	private static String descriptionOf(Object[] row) {
		return (String) row[COL_DESCRIPTION];
	}

	@Test
	void testSimpleSortAsc() {
		final String sql = "SELECT * FROM " + TABLE + " WHERE TRUE ORDER BY age ASC LIMIT 0, " + fillCount();
		final List<Object[]> result = selectRows(sql);

		assertNotNull(result);
		assertEquals(fillCount(), result.size());

		int previousAge = -1;
		for (Object[] row : result) {
			if (previousAge != -1) {
				assertTrue(ageOf(row) >= previousAge,
						"Entities should be sorted by age in ascending order");
			}
			previousAge = ageOf(row);
		}
	}

	@Test
	void testSimpleSortDesc() {
		final String sql = "SELECT * FROM " + TABLE + " WHERE TRUE ORDER BY age DESC LIMIT 0, " + fillCount();
		final List<Object[]> result = selectRows(sql);

		assertNotNull(result);
		assertEquals(fillCount(), result.size());

		int previousAge = Integer.MAX_VALUE;
		for (Object[] row : result) {
			if (previousAge != Integer.MAX_VALUE) {
				assertTrue(ageOf(row) <= previousAge,
						"Entities should be sorted by age in descending order");
			}
			previousAge = ageOf(row);
		}
	}

	@Test
	void testCompositeSort() {
		final String sql = "SELECT * FROM " + TABLE + " WHERE TRUE ORDER BY name ASC, salary DESC LIMIT 0, " + fillCount();
		final List<Object[]> result = selectRows(sql);

		assertNotNull(result);
		assertEquals(fillCount(), result.size());

		String previousName = null;
		double previousSalary = -1;

		for (Object[] row : result) {
			if (previousName != null) {
				final int nameComparison = nameOf(row).compareTo(previousName);

				if (nameComparison == 0) {
					assertTrue(salaryOf(row) <= previousSalary,
							"For same names, salaries should be in descending order");
				} else {
					assertTrue(nameComparison > 0,
							"Names should be in ascending order");
				}
			}

			previousName = nameOf(row);
			previousSalary = salaryOf(row);
		}
	}

	@Test
	void testPagination() {
		final int pageSize = 100;
		final int pageNumber = 2;

		final String sql = String.format(
				"SELECT * FROM " + TABLE + " WHERE TRUE ORDER BY age ASC LIMIT %d, %d",
				pageNumber * pageSize, pageSize);

		final List<Object[]> result = selectRows(sql);

		assertNotNull(result);
		assertEquals(pageSize, result.size());

		final String fullSql = "SELECT * FROM " + TABLE + " WHERE TRUE ORDER BY age ASC LIMIT 0, " + fillCount();
		final List<Object[]> fullResult = selectRows(fullSql);

		for (int i = 0; i < pageSize; i++) {
			assertEquals(fullResult.get(pageNumber * pageSize + i)[COL_ID], result.get(i)[COL_ID],
					"Page should contain correct entities from full sorted list");
		}
	}

	@Test
	void testFilterAndSort() {
		final String sql = "SELECT * FROM " + TABLE
				+ " WHERE age > 25 AND salary < 4000 ORDER BY name DESC LIMIT 0, " + fillCount();
		final List<Object[]> result = selectRows(sql);

		assertNotNull(result);

		String previousName = null;

		for (Object[] row : result) {
			assertTrue(ageOf(row) > 25, "Age should be greater than 25");
			assertTrue(salaryOf(row) < 4000, "Salary should be less than 4000");

			if (previousName != null) {
				assertTrue(nameOf(row).compareTo(previousName) <= 0,
						"Names should be in descending order");
			}

			previousName = nameOf(row);
		}
	}

	@Test
	void testFilterThenSortStrategyWithLargeResult() {
		final String sql = "SELECT * FROM " + TABLE
				+ " WHERE age BETWEEN 20 AND 80 ORDER BY description ASC LIMIT 0, " + fillCount();
		final List<Object[]> result = selectRows(sql);

		assertNotNull(result);

		String previousDescription = null;
		for (Object[] row : result) {
			assertTrue(ageOf(row) >= 20 && ageOf(row) <= 80,
					"Age should be between 20 and 80");

			if (previousDescription != null) {
				assertTrue(descriptionOf(row).compareTo(previousDescription) >= 0,
						"Descriptions should be in ascending order");
			}

			previousDescription = descriptionOf(row);
		}
	}

	@Test
	void testBPTreeIndexScanStrategy() {
		final String sql = "SELECT * FROM " + TABLE
				+ " WHERE age > 10 ORDER BY age ASC LIMIT 0, " + fillCount();
		final List<Object[]> result = selectRows(sql);

		assertNotNull(result);

		int previousAge = -1;
		for (Object[] row : result) {
			assertTrue(ageOf(row) > 10, "Age should be greater than 10");

			if (previousAge != -1) {
				assertTrue(ageOf(row) >= previousAge,
						"Ages should be in ascending order");
			}

			previousAge = ageOf(row);
		}
	}

	@Test
	void testCompositeIndexUsage() {
		final String sql = "SELECT * FROM " + TABLE
				+ " WHERE name = 'Name-5' AND age = 30 ORDER BY salary DESC LIMIT 0, " + fillCount();
		final List<Object[]> result = selectRows(sql);

		assertNotNull(result);

		for (Object[] row : result) {
			assertEquals("Name-5", nameOf(row));
			assertEquals(30, ageOf(row));
		}

		double previousSalary = Double.MAX_VALUE;
		for (Object[] row : result) {
			assertTrue(salaryOf(row) <= previousSalary,
					"Salaries should be in descending order");
			previousSalary = salaryOf(row);
		}
	}

	@Test
	void testExplainQueryPlan() {
		final ExplainQuery.QueryPlan queryPlan = new ExplainQuery.QueryPlan(UUID.randomUUID().toString());
		final String sql = "SELECT * FROM " + TABLE + " WHERE age > 25 ORDER BY name ASC LIMIT 0, 50";

		final List<byte[]> result = selectKeys(queryPlan, sql);

		assertNotNull(result);
		assertTrue(result.size() <= 50);
		assertNotNull(queryPlan.getRootNode());
	}

	@Test
	void testSelectStarNoWhereReturnsRows() {
		final String sql = "SELECT * FROM " + TABLE + " LIMIT 0, " + Math.min(100, fillCount());
		final List<Object[]> result = selectRows(sql);
		assertNotNull(result);
		assertFalse(result.isEmpty());
		assertEquals(Math.min(100, fillCount()), result.size());
	}

	@Test
	void testUnindexedDescriptionResidualFilter() {
		final String targetDesc = firstDescription;
		final String sql = "SELECT * FROM " + TABLE + " WHERE description = '" + targetDesc
				+ "' LIMIT 0, " + fillCount();
		final List<Object[]> result = selectRows(sql);
		assertNotNull(result);
		assertFalse(result.isEmpty());
		for (Object[] row : result) {
			assertEquals(targetDesc, descriptionOf(row));
		}
	}

	@Test
	void testEdgeCases() {
		final String emptySql = "SELECT * FROM " + TABLE + " WHERE age > 1000 LIMIT 0, " + fillCount();
		final List<Object[]> emptyResult = selectRows(emptySql);
		assertNotNull(emptyResult);
		assertTrue(emptyResult.isEmpty());

		final String limitZeroSql = "SELECT * FROM " + TABLE + " WHERE TRUE LIMIT 0, 0";
		final List<Object[]> limitZeroResult = selectRows(limitZeroSql);
		assertNotNull(limitZeroResult);
		assertTrue(limitZeroResult.isEmpty());

		final String largeOffsetSql = "SELECT * FROM " + TABLE + " ORDER BY age ASC LIMIT 10000, 10";
		final List<Object[]> largeOffsetResult = selectRows(largeOffsetSql);
		assertNotNull(largeOffsetResult);
		assertEquals(10, largeOffsetResult.size());
	}

	@Test
	void testTraversePagingOrderSimulation() {
		final String sql = "SELECT * FROM " + TABLE + " WHERE TRUE ORDER BY age ASC LIMIT 200, 100";
		final List<Object[]> result = selectRows(sql);

		assertNotNull(result);
		assertEquals(100, result.size());

		final String fullSql = "SELECT * FROM " + TABLE + " WHERE TRUE ORDER BY age ASC LIMIT 0, " + fillCount();
		final List<Object[]> fullResult = selectRows(fullSql);

		for (int i = 0; i < 100; i++) {
			assertEquals(fullResult.get(200 + i)[COL_ID], result.get(i)[COL_ID],
					"Paged result should match corresponding elements from full sorted list");
		}

		int previousAge = -1;
		for (Object[] row : result) {
			if (previousAge != -1) {
				assertTrue(ageOf(row) >= previousAge,
						"Entities on page should maintain sort order");
			}
			previousAge = ageOf(row);
		}
	}

	@Test
	void testComplexFilterAndSortScenario() {
		final String sql = "SELECT * FROM " + TABLE
				+ " WHERE age BETWEEN 30 AND 50 AND salary > 2000 AND active = TRUE"
				+ " ORDER BY name DESC, salary ASC LIMIT 0, 50";
		final List<Object[]> result = selectRows(sql);

		assertNotNull(result);
		assertTrue(result.size() <= 50);

		for (Object[] row : result) {
			assertTrue(ageOf(row) >= 30 && ageOf(row) <= 50,
					"Age should be between 30 and 50");
			assertTrue(salaryOf(row) > 2000,
					"Salary should be greater than 2000");
			assertTrue(activeOf(row),
					"Entity should be active");
		}

		String previousName = null;
		double previousSalary = -1;

		for (Object[] row : result) {
			if (previousName != null) {
				final int nameComparison = nameOf(row).compareTo(previousName);

				if (nameComparison == 0) {
					assertTrue(salaryOf(row) >= previousSalary,
							"For same names, salaries should be in ascending order");
				} else {
					assertTrue(nameComparison < 0,
							"Names should be in descending order");
				}
			}

			previousName = nameOf(row);
			previousSalary = salaryOf(row);
		}
	}
}