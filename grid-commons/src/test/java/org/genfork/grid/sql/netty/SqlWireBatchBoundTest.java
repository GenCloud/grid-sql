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
package org.genfork.grid.sql.netty;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wire round-trip for BATCH_EXEC binds including {@code byte[]}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class SqlWireBatchBoundTest {
	@Test
	void batchStatementWithBytesRoundTrip() {
		final byte[] payload = new byte[]{1, 2, 3, 4};
		final List<SqlWire.BatchStatement> in = List.of(
				new SqlWire.BatchStatement(
						"INSERT INTO t VALUES (?, ?)",
						new Object[]{1, payload}));
		final byte[] wire = SqlWire.batchExecStatements(7, in);
		final SqlWire.BatchExecPayload out = SqlWire.readBatchExec(wire);
		assertEquals(7, out.sessionId());
		assertEquals(1, out.statements().size());
		final SqlWire.BatchStatement st = out.statements().getFirst();
		assertEquals("INSERT INTO t VALUES (?, ?)", st.sql());
		assertEquals(2, st.binds().length);
		assertEquals(1, st.binds()[0]);
		assertArrayEquals(payload, (byte[]) st.binds()[1]);
	}
}
