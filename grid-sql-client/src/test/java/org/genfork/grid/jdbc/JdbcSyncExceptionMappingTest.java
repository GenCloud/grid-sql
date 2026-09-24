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
package org.genfork.grid.jdbc;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQLException mapping coverage for {@link JdbcSync}.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
class JdbcSyncExceptionMappingTest {
	@Test
	void toSqlExceptionPassesThroughSqlException() {
		final SQLException original = new SQLException("keep");
		assertSame(original, JdbcSync.toSqlException(original));
	}

	@Test
	void toSqlExceptionMapsMessageAndSqlState() {
		final SQLException ex = JdbcSync.toSqlException(new IllegalStateException("UNIQUE violation"));
		assertTrue(ex.getMessage().contains("UNIQUE"));
		assertEquals("23505", ex.getSQLState());
	}

	@Test
	void toSqlExceptionMapsCheckAndFk() {
		assertEquals("23514", JdbcSync.toSqlException(new RuntimeException("CHECK violation")).getSQLState());
		assertEquals("23503", JdbcSync.toSqlException(new RuntimeException("FOREIGN KEY")).getSQLState());
	}
}