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
package org.genfork.grid.sql.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client SQL helpers for PREPARE/PIN/SET.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
class SqlClientSqlTest {
	@Test
	void parsePreparePreservesBodySpaces() {
		final SqlClientSql.PrepareParts parts = SqlClientSql.tryParsePrepare(
				"PREPARE q AS SELECT id FROM t WHERE id = 1");
		assertNotNull(parts);
		assertEquals("q", parts.name());
		assertTrue(parts.body().contains(" FROM "));
	}

	@Test
	void pinAndSetSql() {
		assertEquals("PIN KEY users 42 TTL 1000", SqlClientSql.pinSql("users", 42, 1000L, null));
		assertEquals("UNPIN KEY users 42", SqlClientSql.unpinSql("users", 42));
		assertEquals("SET SCHEMA app", SqlClientSql.setSchemaSql("app"));
		assertEquals("SET REMOTE_DIRTY TRUE", SqlClientSql.setRemoteDirtySql(true));
	}

	@Test
	void fetchWindowFromUrl() {
		final GridSqlUri uri = GridSqlUri.parse(
				"grid://u:p@127.0.0.1:15432/public?fetchWindow=128");
		assertEquals(128, uri.options().fetchWindow());
	}
}