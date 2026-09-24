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
package index.unit.sql;

import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.filters.impl.AlwaysTrueCondition;
import org.genfork.grid.query.filters.impl.LogicalOperatorCondition;
import org.genfork.grid.sql.exec.SqlJoinFilterPush;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Join-col WHERE push: a_id = ? to left id = ? for ON id = a_id.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlJoinFilterPushTest {

	@Test
	void remapsRightJoinColEqOntoLeftPk() {
		final TableSchema left = TableSchema.builder("a")
				.primaryKey("id", SqlType.INT)
				.column("val", SqlType.VARCHAR)
				.build();
		final FilterCondition where = new LogicalOperatorCondition(
				"a_id", LogicalOperatorCondition.Operator.EQ, 7);
		final FilterCondition pushed = SqlJoinFilterPush.remapJoinColToLeft(
				where, "id", "a_id", left);
		assertFalse(pushed instanceof AlwaysTrueCondition);
		final LogicalOperatorCondition eq = assertInstanceOf(LogicalOperatorCondition.class, pushed);
		assertEquals("id", eq.getField());
		assertEquals(LogicalOperatorCondition.Operator.EQ, eq.getOperator());
		assertEquals(7, ((Number) eq.getValues()[0]).intValue());
	}

	@Test
	void unknownRightColumnStaysAlwaysTrue() {
		final TableSchema left = TableSchema.builder("a")
				.primaryKey("id", SqlType.INT)
				.build();
		final FilterCondition where = new LogicalOperatorCondition(
				"label", LogicalOperatorCondition.Operator.EQ, "x");
		final FilterCondition pushed = SqlJoinFilterPush.remapJoinColToLeft(
				where, "id", "a_id", left);
		assertTrue(pushed instanceof AlwaysTrueCondition);
	}
}