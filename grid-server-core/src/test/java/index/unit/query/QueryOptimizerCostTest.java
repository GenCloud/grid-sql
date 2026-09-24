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

import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.ArrayIndexType;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.query.plan.PagingData;
import org.genfork.grid.query.plan.QueryCardinality;
import org.genfork.grid.query.plan.QueryData;
import org.genfork.grid.query.plan.QueryOptimizer;
import org.genfork.grid.query.plan.SortOrderData;
import org.genfork.grid.query.plan.TableRowStats;
import org.genfork.grid.query.plan.strategy.TableScanStrategy;
import org.genfork.grid.sql.exec.SqlExplainKinds;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.4 cost/stats planner unit coverage for {@link QueryCardinality} / {@link QueryOptimizer}.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
class QueryOptimizerCostTest {

	@Test
	void estimateTableRowsPrefersLargerOfMapAndSealed() {
		assertEquals(100L, QueryCardinality.estimateTableRows(40L, 100L));
		assertEquals(80L, QueryCardinality.estimateTableRows(80L, 10L));
		assertEquals(1L, QueryCardinality.estimateTableRows(0L, 0L));
	}

	@Test
	void fanOutAndAndSelectivityAreCrudeButStable() {
		assertEquals(10L, QueryCardinality.estimateFanOut(1000L, 100L));
		assertEquals(10L, QueryCardinality.estimateEq(10L));
		assertEquals(30L, QueryCardinality.estimateIn(10L, 3, 1000L));
		assertEquals(10L, QueryCardinality.estimateAnd(100L, 100L, 1000L));
	}

	@Test
	void chooseJoinStrategyPrefersPkWhenCheaper() {
		final QueryOptimizer.JoinChoice pk = QueryOptimizer.chooseJoinStrategy(false, true, 1000L, 1000L);
		assertEquals(SqlExplainKinds.JOIN_PK, pk.kind());
		assertTrue(pk.estimatedCost() > 0.0);

		final QueryOptimizer.JoinChoice hash = QueryOptimizer.chooseJoinStrategy(false, false, 100L, 100L);
		assertEquals(SqlExplainKinds.JOIN_HASH, hash.kind());
	}

	@Test
	void chooseScanStrategyWithoutOrderIsTable() {
		final QueryData data = new QueryData("t", null, null, new PagingData(0, 10), null, null, List.of());
		final IndexOperationResult result = new IndexOperationResult(Collections.emptySet());
		result.setSize(50L);
		final QueryOptimizer.ScanChoice choice = QueryOptimizer.chooseBestScanStrategy(
				data,
				Map.of(),
				Map.of(),
				result,
				TableRowStats.fromLive(50L, 0L)
		);
		assertTrue(choice.strategy() instanceof TableScanStrategy);
		assertEquals(SqlExplainKinds.TABLE, choice.kind());
		assertTrue(choice.estimatedCost() >= 0.0);
	}

	@Test
	void chooseScanStrategyWithoutExternalOrderFallsBackToTableScan() {
		final SortOrderData[] order = new SortOrderData[]{
				new SortOrderData("age", SortOrderData.OrderDirection.ASC)
		};
		final QueryData data = new QueryData("t", null, null, new PagingData(0, 5), order, null, List.of());
		final IndexOperationResult result = new IndexOperationResult(Collections.emptySet());
		result.setSize(20L);
		final Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> indexes = new HashMap<>();
		final QueryOptimizer.ScanChoice choice = QueryOptimizer.chooseBestScanStrategy(
				data,
				indexes,
				new HashMap<String, ArrayIndexType>(),
				result,
				TableRowStats.fromLive(10_000L, 0L)
		);
		// FilterThenSort requires external-order ArrayIndexType; empty map → table scan.
		assertTrue(choice.strategy() instanceof TableScanStrategy);
		assertEquals(SqlExplainKinds.TABLE, choice.kind());
		assertTrue(choice.estimatedCost() >= 0.0);
	}
}