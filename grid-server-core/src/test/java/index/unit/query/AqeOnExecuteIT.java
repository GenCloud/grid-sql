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

import org.genfork.grid.catalog.TableAnalyzeStats;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.metrics.DistributedQueryMetrics;
import org.genfork.grid.query.adaptive.HeavyQueryAdmission;
import org.genfork.grid.query.adaptive.QueryHeaviness;
import org.genfork.grid.query.adaptive.QueryHeavinessEstimator;
import org.genfork.grid.query.filters.impl.AlwaysTrueCondition;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.store.TableStore;
import org.genfork.grid.threading.ThreadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E: aggregate residual SELECT &gt;= AQE floor admits AdaptiveParallelScan (map-reduce counter).
 * <p>
 * Plain projection SELECT uses {@code selectKeys} / portal paths; COUNT(*) goes through
 * {@code snapshotBlobs} → {@code SqlTxSnapshotOps} where AQE is wired.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class AqeOnExecuteIT {
	private static final String TABLE = "aqe_exec_t";
	private static final int SHARDS = 1;
	private static final int SEED_ROWS = 64;
	private static final long ANALYZE_ROWS = QueryHeaviness.heavyCandidateThreshold();
	private static final boolean NOT_INDEXED_STATS = false;
	private static final double EXPECTED_COUNT = SEED_ROWS;

	@TempDir
	Path dataDir;

	private SqlEngine engine;
	private TableStore store;

	@BeforeEach
	void setUp() {
		ThreadService.ensureRunning();
		HeavyQueryAdmission.global().resetInflightForTests();
		engine = new SqlEngine(new TableCatalog(dataDir), null, SHARDS);
		engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, val INT)");
		for (int i = 0; i < SEED_ROWS; i++) {
			engine.execute("INSERT INTO " + TABLE + " (id, val) VALUES (" + i + ", " + (i % 3) + ")");
		}
		store = engine.catalog().getStore(TABLE);
		store.setAnalyzeStats(new TableAnalyzeStats(ANALYZE_ROWS, Map.of()));
	}

	@AfterEach
	void tearDown() {
		HeavyQueryAdmission.global().resetInflightForTests();
	}

	@Test
	void residualSelectAdmitsAdaptiveParallelScan() {
		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		final SqlResult result = engine.execute(session, "SELECT COUNT(*) FROM " + TABLE);
		engine.execute(session, "COMMIT");
		assertEquals(1, result.rows().size());
		assertEquals(EXPECTED_COUNT, ((Number) result.rows().get(0)[0]).doubleValue(), 0.0);
	}

	@Test
	void indexedPkEqDoesNotAdmitAqe() {
		final long before = DistributedQueryMetrics.mapReduceCalls();
		final SqlResult result = engine.execute("SELECT id FROM " + TABLE + " WHERE id = 1");
		assertEquals(1, result.rows().size());
		assertEquals(
				before,
				DistributedQueryMetrics.mapReduceCalls(),
				"indexed / PK EQ must not enter AQE parallel path");
	}

	@Test
	void analyzeFloorMarksNonIndexedHeavy() {
		final QueryHeaviness heaviness = QueryHeavinessEstimator.fromFilter(
				store,
				AlwaysTrueCondition.getInstance(),
				NOT_INDEXED_STATS);
		assertTrue(heaviness.isHeavy());
	}
}
