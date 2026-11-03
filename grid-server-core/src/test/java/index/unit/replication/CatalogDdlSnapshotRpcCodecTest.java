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
package index.unit.replication;

import org.genfork.grid.replication.netty.codec.CatalogDdlSnapshotRpcCodec;
import org.genfork.grid.replication.netty.codec.CatalogDdlSnapshotRpcCodec.CatalogDdlSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Catalog DDL snapshot wire round-trip.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class CatalogDdlSnapshotRpcCodecTest {
	@Test
	void roundTrip() {
		final CatalogDdlSnapshot snap = new CatalogDdlSnapshot(
				"n1", 123L, 7L, List.of("CREATE TABLE t (id INT PRIMARY KEY)", "CREATE VIEW v AS SELECT id FROM t"));
		final CatalogDdlSnapshot decoded = CatalogDdlSnapshotRpcCodec.decode(
				CatalogDdlSnapshotRpcCodec.encode(snap));
		assertEquals("n1", decoded.fromNodeId());
		assertEquals(123L, decoded.catalogFingerprint());
		assertEquals(7L, decoded.baseEpochExclusive());
		assertEquals(2, decoded.compactLines().size());
		assertEquals(snap.compactLines().get(0), decoded.compactLines().get(0));
	}
}
