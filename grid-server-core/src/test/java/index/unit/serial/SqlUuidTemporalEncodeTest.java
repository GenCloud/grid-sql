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
package index.unit.serial;

import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.SqlTypeCoercion;
import org.genfork.grid.catalog.SqlTypeWireSizes;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.serial.RowEncoder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * UUID / DATE / TIME / TIMESTAMPTZ logical-row encode/decode.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
class SqlUuidTemporalEncodeTest {
	@Test
	void uuidRoundTripFixedSixteenBytes() {
		final UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
		final byte[] wire = SqlTypeCoercion.uuidToBytes(id);
		assertEquals(SqlTypeWireSizes.UUID_BYTES, wire.length);
		assertEquals(id, SqlTypeCoercion.uuidFromBytes(wire));

		final TableSchema schema = TableSchema.builder("t")
				.primaryKey("id", SqlType.UUID)
				.column("note", SqlType.VARCHAR)
				.build();
		final byte[] row = RowEncoder.encode(schema, new Object[]{id, "x"});
		final Object[] decoded = RowEncoder.decode(schema, row);
		assertEquals(id, decoded[0]);
		assertEquals("x", decoded[1]);
	}

	@Test
	void uuidNullIsEmptySpan() {
		final TableSchema schema = TableSchema.builder("t")
				.primaryKey("id", SqlType.INT)
				.column("u", SqlType.UUID, true)
				.build();
		final byte[] row = RowEncoder.encode(schema, new Object[]{1, null});
		final Object[] decoded = RowEncoder.decode(schema, row);
		assertEquals(1, ((Number) decoded[0]).intValue());
		assertNull(decoded[1]);
	}

	@Test
	void dateTimeTimestamptzRoundTrip() {
		final LocalDate d = LocalDate.of(2026, 9, 17);
		final LocalTime t = LocalTime.of(13, 45, 30, 123_000_000);
		final Instant tz = Instant.parse("2026-09-17T10:45:30.123Z");
		final TableSchema schema = TableSchema.builder("t")
				.primaryKey("id", SqlType.INT)
				.column("d", SqlType.DATE)
				.column("tm", SqlType.TIME)
				.column("tsz", SqlType.TIMESTAMPTZ)
				.column("legacy", SqlType.TIMESTAMP)
				.build();
		final byte[] row = RowEncoder.encode(schema, new Object[]{
				1, d, t, tz, "2026-09-17T13:45:30"
		});
		final Object[] decoded = RowEncoder.decode(schema, row);
		assertEquals(d, decoded[1]);
		assertEquals(t, decoded[2]);
		assertEquals(tz, decoded[3]);
		assertEquals("2026-09-17T13:45:30", decoded[4]);
	}

	@Test
	void cursorRewritePreservesUuid() {
		final UUID id = UUID.randomUUID();
		final TableSchema schema = TableSchema.builder("t")
				.primaryKey("id", SqlType.INT)
				.column("u", SqlType.UUID)
				.build();
		final byte[] row = RowEncoder.encode(schema, new Object[]{1, id});
		final LogicalFieldCursor c = LogicalFieldCursor.open(schema, row);
		assertEquals(id, c.read(1));
	}

	@Test
	void timestamptzCoercionUsesZone() {
		final Instant i = SqlTypeCoercion.toTimestamptz(
				"2026-09-17T12:00:00", ZoneOffset.ofHours(3));
		assertEquals(Instant.parse("2026-09-17T09:00:00Z"), i);
	}

	@Test
	void coerceUuidFromString() {
		assertEquals(
				UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
				RowEncoder.coerceTo(SqlType.UUID, "550e8400-e29b-41d4-a716-446655440000"));
	}

	@Test
	void uuidWireBytesStable() {
		final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
		final byte[] a = SqlTypeCoercion.uuidToBytes(id);
		final byte[] b = SqlTypeCoercion.uuidToBytes(id);
		assertArrayEquals(a, b);
	}
}