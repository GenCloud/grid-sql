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

import java.util.ArrayList;
import java.util.List;

import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.serial.PrimaryKeyCodec;
import org.genfork.grid.serial.RowEncoder;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * IT-R2: hydrate-style {@code indexNowBatch} with two PKs sharing one STRICT UNIQUE.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class StrictUniqueGhostHydrateIT {
	private static final int SHARDS = 4;
	private static final int PK_A = 1;
	private static final int PK_B = 2;

	@Test
	void indexNowBatchTwoPksSameUniqueDoesNotAbort() {
		final SqlEngine engine = SqlBenchHelper.createEngine(SHARDS);
		engine.execute(
				"CREATE TABLE sched ("
						+ "id INT PRIMARY KEY, "
						+ "event_name VARCHAR NOT NULL, "
						+ "scheduler_name VARCHAR NOT NULL)");
		engine.execute("CREATE UNIQUE INDEX sched_u ON sched (event_name, scheduler_name)");
		final TableStore store = SqlBenchHelper.store(engine, "sched");

		final byte[] keyA = PrimaryKeyCodec.encodeArgument(store.schema(), PK_A);
		final byte[] valA = RowEncoder.encode(store.schema(), new Object[]{PK_A, "E", "S"});
		final byte[] keyB = PrimaryKeyCodec.encodeArgument(store.schema(), PK_B);
		final byte[] valB = RowEncoder.encode(store.schema(), new Object[]{PK_B, "E", "S"});

		final GridEntriesProcessor processorA = store.processorForTest(keyA);
		final GridEntriesProcessor processorB = store.processorForTest(keyB);
		processorA.putMapOnly(keyA, valA);
		processorB.putMapOnly(keyB, valB);

		final List<GridEntriesProcessor.AddEntry> batch = new ArrayList<>(2);
		batch.add(new GridEntriesProcessor.AddEntry(null, keyA, valA));
		batch.add(new GridEntriesProcessor.AddEntry(null, keyB, valB));

		assertDoesNotThrow(() -> processorB.indexDeltaNow(batch));

		final List<byte[]> hits = store.lookupEqKeys(
				List.of("event_name", "scheduler_name"),
				new byte[][]{
						SqlWireUtil.toGenericArray("E"),
						SqlWireUtil.toGenericArray("S")
				});
		assertEquals(1, hits.size());
		assertEquals(0, java.util.Arrays.compareUnsigned(keyB, hits.getFirst()));
	}
}
