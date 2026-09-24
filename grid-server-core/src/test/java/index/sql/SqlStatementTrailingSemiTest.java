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
package index.sql;

import org.genfork.grid.sql.SqlStatementParser;
import org.genfork.grid.sql.ast.TxAst.BeginSql;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.genfork.grid.sql.ast.Stmt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Trailing statement terminators must be accepted by ANTLR SEMI*, not hand-trimmed.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlStatementTrailingSemiTest {

	@Test
	void parseAcceptsTrailingSemi() {
		final Stmt stmt = SqlStatementParser.parse("BEGIN;");
		assertInstanceOf(BeginSql.class, stmt);
	}

	@Test
	void parseAcceptsMultipleTrailingSemi() {
		final Stmt stmt = SqlStatementParser.parse("BEGIN;;");
		assertInstanceOf(BeginSql.class, stmt);
	}

	@Test
	void parseWithoutSemiStillWorks() {
		final Stmt stmt = SqlStatementParser.parse("BEGIN");
		assertInstanceOf(BeginSql.class, stmt);
	}

	@Test
	void semiInsideStringLiteralIsNotTerminator() {
		assertDoesNotThrow(() -> SqlStatementParser.parse(
				"SELECT v FROM t WHERE v = 'a;b'"));
	}

	@Test
	void parsePreparedBodyAcceptsTrailingSemi() {
		final Stmt stmt = SqlStatementParser.parsePreparedBody(
				"SELECT v FROM t WHERE id = ?;", null);
		assertInstanceOf(SelectSql.class, stmt);
	}

	@Test
	void emptyRejected() {
		assertThrows(IllegalArgumentException.class, () -> SqlStatementParser.parse("   "));
	}
}