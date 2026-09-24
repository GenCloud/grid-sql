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

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.genfork.grid.catalog.TableAnalyzeStats;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.query.adaptive.QueryHeaviness;
import org.genfork.grid.query.adaptive.QueryHeavinessEstimator;
import org.genfork.grid.query.filters.impl.AlwaysTrueCondition;
import org.genfork.grid.query.filters.impl.AndCondition;
import org.genfork.grid.query.filters.impl.LogicalOperatorCondition;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.store.TableStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QueryHeavinessEstimator} + ANALYZE overlay admission floors.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class QueryHeavinessEstimatorIT {
	private static final String TABLE = "hqe_t";
	private static final int SHARDS = 4;
	private static final int TINY_ROWS = 3;
	private static final long HUGE_ROW_COUNT = QueryHeaviness.distributedCandidateThreshold();
	private static final long FAN_OUT_VAL = 100L;
	private static final long FAN_OUT_ID = 50L;
	private static final long TABLE_ROWS_FOR_ESTIMATE = 10_000L;
	private static final boolean NOT_INDEXED = false;
	private static final boolean INDEXED_EQ = true;
	private static final int JOIN_LEFT = 1_000;
	private static final int JOIN_RIGHT = 2_000;
	private static final long OUTER_ROWS = 5_000L;
	private static final long NESTED_CANDIDATES = 800L;
	private static final int SINGLE_SHARD = 1;

	@TempDir
	Path dataDir;

	private SqlEngine engine;
	private TableStore store;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(dataDir), null, SHARDS);
		engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, val INT)");
		for (int i = 0; i < TINY_ROWS; i++) {
			engine.execute("INSERT INTO " + TABLE + " (id, val) VALUES (" + i + ", " + i + ")");
		}
		store = engine.catalog().getStore(TABLE);
	}

	@Test
	void tinyTableWithoutStatsIsNotHeavy() {
		final QueryHeaviness h = QueryHeavinessEstimator.fromFilter(
				store,
				AlwaysTrueCondition.getInstance(),
				NOT_INDEXED
		);
		assertFalse(h.isHeavy());
		assertFalse(h.isDistributedHeavy(store.shardCount()));
	}

	@Test
	void analyzeOverlayFullScanIsHeavy() {
		store.setAnalyzeStats(new TableAnalyzeStats(HUGE_ROW_COUNT, Map.of()));
		final QueryHeaviness h = QueryHeavinessEstimator.fromFilter(
				store,
				AlwaysTrueCondition.getInstance(),
				NOT_INDEXED
		);
		assertTrue(h.isHeavy());
		assertTrue(h.predictedCandidates() >= QueryHeaviness.heavyCandidateThreshold());
	}

	@Test
	void indexedEqNeverHeavyEvenWithHugeStats() {
		store.setAnalyzeStats(new TableAnalyzeStats(HUGE_ROW_COUNT, Map.of()));
		final QueryHeaviness h = QueryHeavinessEstimator.fromFilter(
				store,
				AlwaysTrueCondition.getInstance(),
				INDEXED_EQ
		);
		assertFalse(h.isHeavy());
		assertFalse(h.isDistributedHeavy(store.shardCount()));
	}

	@Test
	void distributedHeavyNeedsMultiShardAndFloor() {
		store.setAnalyzeStats(new TableAnalyzeStats(HUGE_ROW_COUNT, Map.of()));
		final QueryHeaviness h = QueryHeavinessEstimator.fromFilter(
				store,
				null,
				NOT_INDEXED
		);
		assertTrue(h.isDistributedHeavy(SHARDS));
		assertFalse(h.isDistributedHeavy(SINGLE_SHARD));
		assertTrue(h.predictedCandidates() >= QueryHeaviness.distributedCandidateThreshold());
	}

	@Test
	void estimateFilterAndEqUsesFanOutArithmetic() {
		final TableAnalyzeStats stats = new TableAnalyzeStats(
				TABLE_ROWS_FOR_ESTIMATE,
				Map.of("val", FAN_OUT_VAL, "id", FAN_OUT_ID)
		);
		final LogicalOperatorCondition eqVal = new LogicalOperatorCondition(
				"val", LogicalOperatorCondition.Operator.EQ, 1);
		final LogicalOperatorCondition eqId = new LogicalOperatorCondition(
				"id", LogicalOperatorCondition.Operator.EQ, 1);
		final long eqOnly = QueryHeavinessEstimator.estimateFilter(stats, eqVal, TABLE_ROWS_FOR_ESTIMATE);
		assertEquals(FAN_OUT_VAL, eqOnly);

		final long andEst = QueryHeavinessEstimator.estimateFilter(
				stats,
				new AndCondition(eqVal, eqId),
				TABLE_ROWS_FOR_ESTIMATE
		);
		final long expectedAnd = Math.max(
				1L,
				Math.min(TABLE_ROWS_FOR_ESTIMATE, (FAN_OUT_VAL * FAN_OUT_ID) / TABLE_ROWS_FOR_ESTIMATE)
		);
		assertEquals(expectedAnd, andEst);
	}

	@Test
	void fromJoinSidesAndSubqueryBrief() {
		final QueryHeaviness join = QueryHeavinessEstimator.fromJoinSides(
				JOIN_LEFT, JOIN_RIGHT, NOT_INDEXED);
		assertEquals(JOIN_LEFT * JOIN_RIGHT, join.predictedCandidates());
		assertTrue(join.isHeavy());
		assertFalse(QueryHeavinessEstimator.fromJoinSides(JOIN_LEFT, JOIN_RIGHT, INDEXED_EQ).isHeavy());

		final QueryHeaviness sub = QueryHeavinessEstimator.fromSubquery(
				OUTER_ROWS, NESTED_CANDIDATES, NOT_INDEXED);
		assertTrue(sub.predictedCandidates() > 0);
	}
}
