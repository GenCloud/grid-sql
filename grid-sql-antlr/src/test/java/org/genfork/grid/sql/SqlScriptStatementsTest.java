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

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlScriptStatements} ANTLR script split.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
class SqlScriptStatementsTest {
	@Test
	void singleStatementNoSemi() {
		final List<String> parts = SqlScriptStatements.splitExecutables("SELECT id FROM t");
		assertEquals(1, parts.size());
		assertEquals("SELECT id FROM t", parts.getFirst());
	}

	@Test
	void singleStatementTrailingSemi() {
		final List<String> parts = SqlScriptStatements.splitExecutables("SELECT id FROM t;");
		assertEquals(1, parts.size());
		assertEquals("SELECT id FROM t", parts.getFirst());
	}

	@Test
	void multiStatementScript() {
		final List<String> parts = SqlScriptStatements.splitExecutables(
				"INSERT INTO t (id) VALUES (1); SELECT id FROM t WHERE id = 1;");
		assertEquals(2, parts.size());
		assertEquals("INSERT INTO t (id) VALUES (1)", parts.get(0));
		assertEquals("SELECT id FROM t WHERE id = 1", parts.get(1));
	}

	@Test
	void blankReturnsEmpty() {
		assertTrue(SqlScriptStatements.splitExecutables("   ").isEmpty());
		assertTrue(SqlScriptStatements.splitExecutables(null).isEmpty());
	}

	@Test
	void badScriptRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> SqlScriptStatements.splitExecutables("NOT A SCRIPT;;;"));
	}

	@Test
	void preservesInnerSpaces() {
		final List<String> parts = SqlScriptStatements.splitExecutables(
				"SELECT  id  FROM  t  ;  SELECT id FROM t WHERE id = 2");
		assertEquals(2, parts.size());
		assertTrue(parts.get(0).contains("  id  "));
	}
}