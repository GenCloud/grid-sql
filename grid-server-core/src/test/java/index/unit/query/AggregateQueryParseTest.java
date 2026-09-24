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

import org.genfork.grid.query.plan.AggregateSpec;
import org.genfork.grid.query.plan.JoinSpec;
import org.genfork.grid.query.plan.QueryData;
import org.genfork.grid.query.plan.QueryParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Aggregate / JOIN clauses from ANTLR → QueryData.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class AggregateQueryParseTest {

	@Test
	void countGroupByParsedIntoQueryData() {
		final QueryData data = QueryParser.parseAndBuildCondition(null, "SELECT COUNT(*) FROM orders GROUP BY macroRegion", java.util.Map.of()
		);
		assertTrue(data.isAggregate());
		final AggregateSpec agg = data.aggregate();
		assertNotNull(agg);
		assertTrue(agg.countStar());
		assertEquals("macroRegion", agg.groupByColumn());
		assertEquals("orders", data.table());
	}

	@Test
	void sumGroupByParsed() {
		final QueryData data = QueryParser.parseAndBuildCondition(null, "SELECT SUM(score) FROM orders GROUP BY macroRegion", java.util.Map.of()
		);
		assertTrue(data.isAggregate());
		assertFalse(data.aggregate().countStar());
		assertEquals("score", data.aggregate().sumColumn());
		assertFalse(data.aggregate().avg());
		assertEquals("macroRegion", data.aggregate().groupByColumn());
	}

	@Test
	void avgGroupByParsed() {
		final QueryData data = QueryParser.parseAndBuildCondition(null, "SELECT AVG(score) FROM orders GROUP BY macroRegion", java.util.Map.of()
		);
		assertTrue(data.aggregate().avg());
		assertEquals("score", data.aggregate().sumColumn());
	}

	@Test
	void joinParsed() {
		final QueryData data = QueryParser.parseAndBuildCondition(null, "SELECT * FROM orders JOIN Other ON id = otherId", java.util.Map.of()
		);
		assertTrue(data.isJoin());
		final JoinSpec join = data.joins().getFirst();
		assertEquals("Other", join.rightTable());
		assertEquals("id", join.leftColumn());
		assertEquals("otherId", join.rightColumn());
	}

	@Test
	void plainSelectHasNoAggregate() {
		final QueryData data = QueryParser.parseAndBuildCondition(null, "SELECT * FROM orders WHERE id = 1", java.util.Map.of()
		);
		assertNull(data.aggregate());
	}

	@Test
	void multiColumnGroupByParsedIntoQueryData() {
		final QueryData data = QueryParser.parseAndBuildCondition(
				null, "SELECT COUNT(*) FROM orders GROUP BY macroRegion, bucket", java.util.Map.of());
		assertTrue(data.isAggregate());
		assertEquals(2, data.aggregate().groupByColumns().size());
		assertEquals("macroRegion", data.aggregate().groupByColumn());
		assertEquals("bucket", data.aggregate().groupByColumns().get(1));
	}
}
