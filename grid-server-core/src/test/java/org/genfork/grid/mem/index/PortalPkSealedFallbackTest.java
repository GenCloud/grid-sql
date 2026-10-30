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
package org.genfork.grid.mem.index;

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
 * Package-local: sealed-bound PK is portal-ineligible, not {@code PRIMARY KEY index missing}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class PortalPkSealedFallbackTest {
	private static final String TABLE = "portal_pk_sealed";
	private static final String PK = "id";

	@TempDir
	Path temporary;

	@Test
	void sealedBoundPkIsPortalIneligibleNotMissing() throws Exception {
		final TableSchema schema = TableSchema.builder(TABLE)
				.primaryKey(PK, SqlType.INT)
				.column("payload", SqlType.INT)
				.build();
		final GridCompositeIndex index = new GridCompositeIndex(schema);
		assertTrue(index.isRamPrimaryKeyBptree(), "plain RAM PK must be portal-eligible");

		final Path file = temporary.resolve("id.sbpt");
		SealedBPTreeWriter.write(file, TABLE, 0, PK,
				List.of(new SealedBPTreeWriter.Entry(
						"00000001".getBytes(StandardCharsets.UTF_8),
						"row-1".getBytes(StandardCharsets.UTF_8))));

		try (SealedBPTreeReader reader = SealedBPTreeReader.open(file)) {
			index.installSealedFallback(PK, reader);

			assertFalse(index.isRamPrimaryKeyBptree(),
					"sealed readers on PK must disable RAM portal cursor");

			final PrimaryKeyPortalUnavailableException portalEx = assertThrows(
					PrimaryKeyPortalUnavailableException.class,
					index::openPrimaryKeyRowCursor);
			assertFalse(
					portalEx.getMessage().contains("PRIMARY KEY index missing"),
					"must not misreport sealed-bound PK as missing");

			final AtomicBoolean visited = new AtomicBoolean(false);
			assertDoesNotThrow(() -> index.forEachSelectKeys(
					"SELECT id, payload FROM " + TABLE,
					key -> {
						visited.set(true);
						return true;
					}));
		} finally {
			index.cancelDumpStats();
		}
	}
}
