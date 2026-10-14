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
package org.genfork.grid.sql;

import org.genfork.grid.catalog.SqlTypeCoercion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Hex format/parse and fail-closed {@link SqlTypeCoercion#toBytes}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class SqlBytesLiteralsTest {
	@Test
	void formatAndParseRoundTrip() {
		final byte[] raw = new byte[]{0x00, 0x7f, (byte) 0xff, 0x0a};
		assertEquals("X'007FFF0A'", SqlBytesLiterals.formatXHex(raw));
		assertArrayEquals(raw, SqlBytesLiterals.parseXHexToken("X'007FFF0A'"));
		assertArrayEquals(raw, SqlBytesLiterals.parseXHexToken("x'007fff0a'"));
	}

	@Test
	void emptyBytes() {
		assertEquals("X''", SqlBytesLiterals.formatXHex(new byte[0]));
		assertArrayEquals(new byte[0], SqlBytesLiterals.parseXHexToken("X''"));
	}

	@Test
	void oddHexRejected() {
		assertThrows(IllegalArgumentException.class, () -> SqlBytesLiterals.parseHex("abc"));
	}

	@Test
	void toBytesFailClosed() {
		assertNull(SqlTypeCoercion.toBytes(null));
		final byte[] b = new byte[]{1, 2};
		assertArrayEquals(b, SqlTypeCoercion.toBytes(b));
		assertThrows(IllegalArgumentException.class, () -> SqlTypeCoercion.toBytes("nope"));
	}

	@Test
	void bindsMaterializeUsesXHex() {
		assertEquals("X'0102'", SqlBinds.toSqlLiteral(new byte[]{1, 2}));
	}
}
