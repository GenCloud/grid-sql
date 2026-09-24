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

import index.sql.SqlBenchHelper;
import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.query.plan.QueryParser;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EQ+LIMIT early-stop and EQ+ORDER+LIMIT ordered leaf cut (TD-PERF-002 Wave C).
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class QueryLimitEarlyStopTest {
	private static final String TABLE = "query_row";
	private static final String UNORDERED_TABLE = "query_row_unordered";
	private static final int LIMIT_N = 100;
	private static final int BUCKET = 7;
	private static final int ROW_COUNT = 5000;

	private TableStore store;
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		QueryParser.clearPlanCache();
		engine = SqlBenchHelper.createEngine(4);
		SqlBenchHelper.ensureIndexedTable(engine, SqlBenchHelper.queryRowSchema(TABLE));
		store = SqlBenchHelper.store(engine, TABLE);
		for (int i = 0; i < ROW_COUNT; i++) {
			SqlBenchHelper.putIndexedRow(engine, TABLE, String.valueOf(i), BUCKET, i);
		}
	}

	@Test
	void eqLimitReturnsAtMostLimit() {
		final List<byte[]> result = store.selectKeys(
				"SELECT * FROM " + TABLE + " WHERE bucket = " + BUCKET + " LIMIT 0, " + LIMIT_N);
		assertEquals(LIMIT_N, result.size());
	}

	@Test
	void eqOrderLimitReturnsAtMostLimit() {
		final List<byte[]> result = store.selectKeys(
				"SELECT * FROM " + TABLE + " WHERE bucket = " + BUCKET
						+ " ORDER BY score ASC LIMIT 0, " + LIMIT_N);
		assertEquals(LIMIT_N, result.size());
		assertTrue(result.getFirst().length > 0);
	}

	/**
	 * Composite (bucket, score) leaf order matches ORDER BY — early LIMIT must keep the
	 * lowest scores, not an arbitrary EQ posting prefix.
	 */
	@Test
	void eqOrderLimitCompositeReturnsLowestScores() {
		final List<Object[]> rows = store.select(
				"SELECT id, score FROM " + TABLE + " WHERE bucket = " + BUCKET
						+ " ORDER BY score ASC LIMIT 0, " + LIMIT_N,
				List.of("id", "score"));
		assertEquals(LIMIT_N, rows.size());
		int prev = Integer.MIN_VALUE;
		for (int i = 0; i < rows.size(); i++) {
			final int score = ((Number) rows.get(i)[1]).intValue();
			assertEquals(i, score, "composite ordered early-stop must return score rank " + i);
			assertTrue(score >= prev);
			prev = score;
		}
	}

	/**
	 * Without a composite covering ORDER BY, EQ must not LIMIT-cut before sort — top-N by
	 * score must still be correct (unordered scan remains full-candidate).
	 */
	@Test
	void eqOrderLimitWithoutCompositeStillCorrectTopN() {
		final TableSchema schema = TableSchema.builder(UNORDERED_TABLE)
				.primaryKey("id", SqlType.VARCHAR)
				.column("bucket", SqlType.INT)
				.column("score", SqlType.INT)
				.externalOrder("score")
				.index(IndexDef.of("idx_bucket", IndexType.LAX, "bucket"))
				.index(IndexDef.of("idx_score", IndexType.LAX, "score"))
				.build();
		SqlBenchHelper.ensureIndexedTable(engine, schema);
		final TableStore unordered = SqlBenchHelper.store(engine, UNORDERED_TABLE);
		for (int i = 0; i < ROW_COUNT; i++) {
			SqlBenchHelper.putIndexedRow(engine, UNORDERED_TABLE, String.valueOf(i), BUCKET, i);
		}

		final List<Object[]> rows = unordered.select(
				"SELECT id, score FROM " + UNORDERED_TABLE + " WHERE bucket = " + BUCKET
						+ " ORDER BY score ASC LIMIT 0, " + LIMIT_N,
				List.of("id", "score"));
		assertEquals(LIMIT_N, rows.size());
		for (int i = 0; i < LIMIT_N; i++) {
			assertEquals(i, ((Number) rows.get(i)[1]).intValue(),
					"unordered EQ+ORDER must sort full candidates before LIMIT");
		}
	}
}
