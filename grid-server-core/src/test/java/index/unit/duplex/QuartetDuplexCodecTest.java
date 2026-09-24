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
package index.unit.duplex;

import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.codec.duplex.DuplexBlob;
import org.genfork.grid.codec.duplex.DuplexCodecSupport;
import org.genfork.grid.codec.duplex.DuplexRepairMode;
import org.genfork.grid.codec.duplex.ParityLane;
import org.genfork.grid.codec.duplex.QuartetDuplexCodec;
import org.genfork.grid.serial.RowEncoder;
import org.genfork.grid.serial.FieldMetaData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Duplex codec round-trip / repair on catalog logical bytes ({@link TableSchema} + {@link RowEncoder}).
 * Catalog / SQL-first row cursor (no domain POJO path).
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class QuartetDuplexCodecTest {
	private TableSchema schema;

	@BeforeEach
	void enableDuplex() {
		schema = TableSchema.builder("duplex_t")
				.primaryKey("id", SqlType.INT)
				.column("name", SqlType.VARCHAR)
				.build();
		DuplexCodecSupport.configure(true, DuplexRepairMode.REBUILD_DATA_FROM_PARITY,
				true, true, 1L, true, true);
	}

	@AfterAll
	void disableDuplex() {
		DuplexCodecSupport.configure(false, DuplexRepairMode.FAIL, false, false, 1L, true, true);
	}

	@Test
	void _t01_singlePassRoundTrip() {
		final byte[] logical = RowEncoder.encode(schema, new Object[]{42, "alpha"});
		final QuartetDuplexCodec codec = DuplexCodecSupport.getCodec();
		final DuplexBlob blob = codec.encodeLogical(logical);
		assertTrue(ParityLane.matches(blob.dataLane(), blob.parityLane()));

		final byte[] decodedLogical = codec.decodeToLogical(blob);
		assertArrayEquals(logical, decodedLogical);
		final Object[] values = RowEncoder.decode(schema, decodedLogical);
		assertEquals(42, values[0]);
		assertEquals("alpha", values[1]);
	}

	@Test
	void _t02_bitFlipRepairFromParity() {
		final byte[] logical = RowEncoder.encode(schema, new Object[]{7, "repair"});
		final QuartetDuplexCodec codec = DuplexCodecSupport.getCodec();
		final DuplexBlob blob = codec.encodeLogical(logical);
		final byte[] data = blob.dataLane();
		data[0] = (byte) (data[0] ^ 0x01);
		final DuplexBlob corrupted = new DuplexBlob(data, blob.parityLane(), blob.logicalLen(), blob.schemaEpoch(),
				DuplexBlob.checksum(data, blob.parityLane(), blob.logicalLen(), blob.schemaEpoch()));

		final DuplexBlob repaired = codec.getVerifier().verifyOrRepair(corrupted);
		assertTrue(ParityLane.matches(repaired.dataLane(), repaired.parityLane()));
		final Object[] values = RowEncoder.decode(schema, codec.decodeToLogical(repaired));
		assertEquals(7, values[0]);
		assertEquals("repair", values[1]);
	}

	@Test
	void _t03_dualCorruptFails() {
		DuplexCodecSupport.configure(true, DuplexRepairMode.FAIL, true, true, 1L, true, true);
		final byte[] logical = RowEncoder.encode(schema, new Object[]{1, "x"});
		final QuartetDuplexCodec codec = DuplexCodecSupport.getCodec();
		final DuplexBlob blob = codec.encodeLogical(logical);
		final byte[] data = blob.dataLane();
		final byte[] parity = blob.parityLane();
		data[0] ^= 0x01;
		parity[0] ^= 0x02;
		final DuplexBlob bad = new DuplexBlob(data, parity, blob.logicalLen(), blob.schemaEpoch(),
				DuplexBlob.checksum(data, parity, blob.logicalLen(), blob.schemaEpoch()));
		assertThrows(IllegalStateException.class, () -> codec.getVerifier().verifyOrRepair(bad));
	}

	@Test
	void _t04_catalogFieldMetaNoUnsafe() {
		final FieldMetaData pk = schema.pkFieldMeta();
		assertEquals("id", pk.getName());
		assertEquals(Integer.class, pk.getType());
		assertEquals(Integer.class, pk.getType());
	}
}