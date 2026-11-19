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

import org.genfork.grid.nio.EncodeBuffers;
import org.genfork.grid.replication.netty.codec.CatalogDdlSnapshotRpcCodec;
import org.genfork.grid.replication.netty.codec.CatalogDdlSnapshotRpcCodec.CatalogDdlSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Catalog DDL snapshot wire round-trip.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class CatalogDdlSnapshotRpcCodecTest {
	private static final byte[] PRIVILEGE_META = new byte[] {0x50, 0x52, 0x49, 0x56, 0x01};

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
		assertEquals(0, decoded.privilegesMetaBytes().length);
	}

	@Test
	void roundTripWithPrivilegeMeta() {
		final CatalogDdlSnapshot snap = new CatalogDdlSnapshot(
				"n1", 123L, 7L, List.of("CREATE TABLE t (id INT PRIMARY KEY)"), PRIVILEGE_META);
		final CatalogDdlSnapshot decoded = CatalogDdlSnapshotRpcCodec.decode(
				CatalogDdlSnapshotRpcCodec.encode(snap));
		assertArrayEquals(PRIVILEGE_META, decoded.privilegesMetaBytes());
	}

	@Test
	void legacyBodyWithoutPrivilegeTail() {
		final byte[] from = "n1".getBytes(StandardCharsets.UTF_8);
		final byte[] line = "CREATE TABLE t (id INT PRIMARY KEY)".getBytes(StandardCharsets.UTF_8);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Integer.BYTES + from.length
						+ Long.BYTES
						+ Long.BYTES
						+ Integer.BYTES
						+ Integer.BYTES + line.length);
		EncodeBuffers.putLengthPrefixed(buf, from);
		buf.putLong(55L);
		buf.putLong(3L);
		buf.putInt(1);
		EncodeBuffers.putLengthPrefixed(buf, line);
		final CatalogDdlSnapshot decoded = CatalogDdlSnapshotRpcCodec.decode(EncodeBuffers.toByteArray(buf));
		assertEquals(55L, decoded.catalogFingerprint());
		assertEquals(1, decoded.compactLines().size());
		assertEquals(0, decoded.privilegesMetaBytes().length);
	}
}
