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
package index.unit.bptree;

import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.GridCompositeIndex;
import org.genfork.grid.mem.index.IndexType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DROP named composite must remove the RAM tree via normalized list key.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class DropNamedIndexCompositeRemoveTest {
	private static final String INDEX = "owner_item_loc_uindex";
	private static final List<String> COLS = List.of("owner_id", "item_id", "location_data");

	@Test
	void dropNamedCompositeRemovesTree() {
		final TableSchema schema = TableSchema.builder("t")
				.primaryKey("id", SqlType.BIGINT)
				.column("owner_id", SqlType.BIGINT)
				.column("item_id", SqlType.INT)
				.column("location_data", SqlType.VARCHAR)
				.build();
		final GridCompositeIndex index = new GridCompositeIndex(schema);
		final IndexDef def = IndexDef.of(INDEX, IndexType.STRICT, COLS.toArray(String[]::new));
		final TableSchema withIndex = schema.withIndex(def, 1L);
		index.addNamedIndex(def, withIndex);
		assertNotNull(index.resolveCompositeIndex(COLS));
		assertTrue(index.hasNamedIndex(INDEX));

		index.dropNamedIndex(INDEX);

		assertFalse(index.hasNamedIndex(INDEX));
		assertNull(index.resolveCompositeIndex(COLS));
		index.cancelDumpStats();
	}
}
