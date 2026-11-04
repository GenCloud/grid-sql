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
package index.unit.catalog;

import org.genfork.grid.catalog.CatalogPersistUtil;
import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.SyntheticPkIndexUtil;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.IndexType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic PK covering-index identification and withoutIndex fail-closed.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class SyntheticPkIndexUtilTest {
	private static final String TABLE = "synth_pk_util";

	@Test
	void detectsSyntheticByNameAndPkColumns() {
		final TableSchema schema = TableSchema.builder(TABLE)
				.primaryKey("id", SqlType.INT)
				.column("v", SqlType.INT)
				.build();
		final IndexDef pk = schema.findIndex(CatalogPersistUtil.syntheticPkIndexName(TABLE));
		assertTrue(SyntheticPkIndexUtil.isSyntheticPrimaryKeyIndex(schema, pk));
		assertTrue(SyntheticPkIndexUtil.isSyntheticPrimaryKeyIndexName(TABLE, pk.name()));
		assertFalse(SyntheticPkIndexUtil.isSyntheticPrimaryKeyIndex(
				schema,
				IndexDef.of("idx_v", IndexType.LAX, "v")));
	}

	@Test
	void withoutIndexRejectsSyntheticPk() {
		final TableSchema schema = TableSchema.builder(TABLE)
				.primaryKey("id", SqlType.INT)
				.column("v", SqlType.INT)
				.build();
		final String pkName = CatalogPersistUtil.syntheticPkIndexName(TABLE);
		final IllegalStateException ex = assertThrows(
				IllegalStateException.class,
				() -> schema.withoutIndex(pkName, schema.schemaEpoch() + 1L));
		assertTrue(ex.getMessage().contains(SyntheticPkIndexUtil.MSG_CANNOT_DROP_PRIMARY_KEY_INDEX));
		assertEquals(
				SyntheticPkIndexUtil.cannotDropMessage(pkName),
				ex.getMessage());
	}
}
