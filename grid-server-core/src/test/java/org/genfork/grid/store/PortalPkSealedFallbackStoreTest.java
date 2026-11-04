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
package org.genfork.grid.store;

import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.exceptions.PrimaryKeyPortalUnavailableException;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeReader;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Package-local: {@link TableStore} sealed-bound PK portal ineligibility.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class PortalPkSealedFallbackStoreTest {
	private static final String TABLE = "portal_pk_sealed_store";
	private static final String PK = "id";
	private static final int SHARDS = 4;

	@TempDir
	Path temporary;

	@Test
	void tableStoreSealedBoundPkPortalIneligibleNotMissing() throws Exception {
		final TableSchema schema = TableSchema.builder(TABLE)
				.primaryKey(PK, SqlType.INT)
				.column("payload", SqlType.INT)
				.build();
		final TableStore store = new TableStore(schema, SHARDS, null);
		try {
			assertTrue(store.isRamPrimaryKeyBptree(), "plain store PK portal-eligible");

			final Path file = temporary.resolve("store-id.sbpt");
			SealedBPTreeWriter.write(file, schema.catalogKey(), 0, PK,
					List.of(new SealedBPTreeWriter.Entry(
							"00000001".getBytes(StandardCharsets.UTF_8),
							"row-1".getBytes(StandardCharsets.UTF_8))));
			try (SealedBPTreeReader reader = SealedBPTreeReader.open(file)) {
				store.compositeIndex().installSealedFallback(PK, reader);

				assertFalse(store.isRamPrimaryKeyBptree(),
						"sealed-bound store PK must be portal-ineligible");
				assertThrows(PrimaryKeyPortalUnavailableException.class,
						store::openPrimaryKeyRowCursor);

				final AtomicBoolean visited = new AtomicBoolean(false);
				assertDoesNotThrow(() -> store.forEachSelectKeys(
						"SELECT id, payload FROM " + schema.catalogKey(),
						key -> {
							visited.set(true);
							return true;
						}));
			}
		} finally {
			store.close();
		}
	}
}
