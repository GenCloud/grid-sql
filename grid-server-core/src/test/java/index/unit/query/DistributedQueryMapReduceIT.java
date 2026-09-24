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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.genfork.grid.query.distributed.DistributedQueryExecutor;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.threading.ThreadService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DistributedQueryExecutor#executeMapReduceStages} map-reduce API.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class DistributedQueryMapReduceIT {
	private static final int LIMIT_UNLIMITED = 0;
	private static final int LIMIT_TWO = 2;
	private static final String STAGE_SQL = "SELECT 1";
	private static final int MIN_STAGE_CALLS = 1;

	@BeforeEach
	void setUp() {
		ThreadService.ensureRunning();
	}

	@Test
	void supplierStagesMergeAllWireChunks() {
		final List<Supplier<List<byte[]>>> stages = List.of(
				() -> List.of(SqlWireUtil.toGenericArray(1), SqlWireUtil.toGenericArray(2)),
				() -> List.of(SqlWireUtil.toGenericArray(3)),
				() -> List.of(SqlWireUtil.toGenericArray(4), SqlWireUtil.toGenericArray(5))
		);
		final List<byte[]> merged = DistributedQueryExecutor.executeMapReduceStages(stages, LIMIT_UNLIMITED);
		assertEquals(5, merged.size());
	}

	@Test
	void functionStagesReceiveSharedSqlAndHonorLimit() {
		final AtomicInteger calls = new AtomicInteger();
		final List<Function<String, List<byte[]>>> stages = new ArrayList<>();
		stages.add(sql -> {
			assertEquals(STAGE_SQL, sql);
			calls.incrementAndGet();
			return List.of(SqlWireUtil.toGenericArray(10), SqlWireUtil.toGenericArray(11));
		});
		stages.add(sql -> {
			assertEquals(STAGE_SQL, sql);
			calls.incrementAndGet();
			return List.of(SqlWireUtil.toGenericArray(12));
		});
		final List<byte[]> merged = DistributedQueryExecutor.executeMapReduceStages(
				STAGE_SQL,
				stages,
				LIMIT_TWO
		);
		assertEquals(LIMIT_TWO, merged.size());
		assertTrue(calls.get() >= MIN_STAGE_CALLS);
	}

	@Test
	void emptyStagesReturnEmpty() {
		final List<byte[]> merged = DistributedQueryExecutor.executeMapReduceStages(List.of(), LIMIT_UNLIMITED);
		assertEquals(0, merged.size());
	}
}