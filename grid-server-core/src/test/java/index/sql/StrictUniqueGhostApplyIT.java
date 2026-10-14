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

import java.util.List;

import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.serial.PrimaryKeyCodec;
import org.genfork.grid.serial.RowEncoder;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * IT-R1: replica-style {@code installCommitted} UPSERT of PK_B while PK_A holds STRICT UNIQUE.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class StrictUniqueGhostApplyIT {
	private static final int SHARDS = 4;
	private static final int PK_A = 10;
	private static final int PK_B = 20;
	private static final String EVENT = "Pledge Game";
	private static final String SCHEDULER = "prepareFight";

	@Test
	void installCommittedReplacesStrictUniqueOwnerAndTomestonesGhost() {
		final SqlEngine engine = SqlBenchHelper.createEngine(SHARDS);
		engine.execute(
				"CREATE TABLE event_schedulers ("
						+ "id INT PRIMARY KEY, "
						+ "event_name VARCHAR NOT NULL, "
						+ "scheduler_name VARCHAR NOT NULL, "
						+ "prev_fire_time TIMESTAMP)");
		engine.execute(
				"CREATE UNIQUE INDEX event_schedulers_uindex "
						+ "ON event_schedulers (event_name, scheduler_name)");
		final TableStore store = SqlBenchHelper.store(engine, "event_schedulers");

		final byte[] keyA = PrimaryKeyCodec.encodeArgument(store.schema(), PK_A);
		final byte[] valA = RowEncoder.encode(store.schema(), new Object[]{PK_A, EVENT, SCHEDULER, null});
		final GridEntriesProcessor processorA = store.processorForTest(keyA);
		processorA.installCommitted(keyA, valA, false);

		final byte[] keyB = PrimaryKeyCodec.encodeArgument(store.schema(), PK_B);
		final byte[] valB = RowEncoder.encode(store.schema(), new Object[]{PK_B, EVENT, SCHEDULER, null});
		final GridEntriesProcessor processorB = store.processorForTest(keyB);
		processorB.installCommitted(keyB, valB, false);

		assertNull(processorA.getCommitted(keyA), "ghost PK_A must be tombstoned");
		final List<byte[]> hits = store.lookupEqKeys(
				List.of("event_name", "scheduler_name"),
				new byte[][]{
						SqlWireUtil.toGenericArray(EVENT),
						SqlWireUtil.toGenericArray(SCHEDULER)
				});
		assertEquals(1, hits.size());
		assertEquals(0, java.util.Arrays.compareUnsigned(keyB, hits.getFirst()));

		final SqlResult rows = engine.execute(
				"SELECT id FROM event_schedulers WHERE event_name = 'Pledge Game' "
						+ "AND scheduler_name = 'prepareFight'");
		assertEquals(1, rows.rows().size());
		assertEquals(PK_B, ((Number) rows.rows().getFirst()[0]).intValue());
	}
}
