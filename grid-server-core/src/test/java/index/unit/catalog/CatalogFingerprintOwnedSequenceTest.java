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

import org.genfork.grid.catalog.CatalogFingerprintUtil;
import org.genfork.grid.catalog.SequenceDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.catalog.TableSchema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Owned IDENTITY sequences must not diverge catalog fingerprints (compact omits them).
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public class CatalogFingerprintOwnedSequenceTest {

	@Test
	void ownedIdentitySequenceDoesNotChangeFingerprint() {
		final TableCatalog withSeq = new TableCatalog();
		final TableCatalog withoutSeq = new TableCatalog();
		final TableSchema schema = TableSchema.builder("t")
				.identityColumn("id", SqlType.INT, true, "t_id_seq")
				.build();
		withSeq.createTable(schema);
		withoutSeq.createTable(schema);
		withSeq.createSequence(
				SequenceDef.of("t_id_seq", SequenceDef.DEFAULT_START, SequenceDef.DEFAULT_INCREMENT, false)
						.withOwner("t", "id"),
				true);
		assertEquals(
				CatalogFingerprintUtil.fingerprint(withoutSeq),
				CatalogFingerprintUtil.fingerprint(withSeq));
	}

	@Test
	void freeSequenceDoesChangeFingerprint() {
		final TableCatalog a = new TableCatalog();
		final TableCatalog b = new TableCatalog();
		a.createTable(TableSchema.builder("t").primaryKey("id", SqlType.INT).build());
		b.createTable(TableSchema.builder("t").primaryKey("id", SqlType.INT).build());
		a.createSequence(SequenceDef.of("free_seq", 1L, 1L, false), true);
		assertNotEquals(
				CatalogFingerprintUtil.fingerprint(a),
				CatalogFingerprintUtil.fingerprint(b));
	}
}
