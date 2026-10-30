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
package index.unit.store;

import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.exceptions.NonUniqueValueException;
import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.serial.PrimaryKeyCodec;
import org.genfork.grid.serial.RowEncoder;
import org.genfork.grid.store.StrictUniqueConstraintUtil;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * STRICT unique: self-owner UPDATE / conflict hit must not throw; new PK colliding with live owner must.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class StrictUniqueSelfOwnerTest {
	private static final int SHARDS = 4;

	@Test
	void selfOwnerUpdateAllowedNewPkRejected() {
		final TableSchema schema = TableSchema.builder("event_schedulers")
				.primaryKey("id", SqlType.INT)
				.column("event_name", SqlType.VARCHAR)
				.column("scheduler_name", SqlType.VARCHAR)
				.index(IndexDef.of(
						"event_schedulers_event_scheduler_uindex",
						IndexType.STRICT,
						"event_name", "scheduler_name"))
				.build();
		final TableStore store = new TableStore(schema, SHARDS, null);
		final Object[] row = new Object[]{1, "Castle", "sched-1"};
		store.upsert(row, false);

		final byte[] key = PrimaryKeyCodec.encodeRow(schema, row);
		final byte[] value = RowEncoder.encode(schema, row);
		assertDoesNotThrow(() ->
				StrictUniqueConstraintUtil.validateSecondaryStrict(store, schema, value, key));

		final Object[] ghost = new Object[]{99, "Castle", "sched-1"};
		final byte[] ghostKey = PrimaryKeyCodec.encodeRow(schema, ghost);
		final byte[] ghostValue = RowEncoder.encode(schema, ghost);
		assertThrows(NonUniqueValueException.class, () ->
				StrictUniqueConstraintUtil.validateSecondaryStrict(store, schema, ghostValue, ghostKey));

		store.close();
	}
}
