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

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.misc.Interval;
import org.genfork.grid.antlr.SimplifiedSqlLexer;
import org.genfork.grid.antlr.SimplifiedSqlParser;

/**
 * Client-side SQL helpers: PREPARE/EXECUTE parse, PIN/SET render, literal escape.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class SqlClientSql {
	private static final String PREPARE = "PREPARE ";
	private static final String AS = " AS ";
	private static final String EXECUTE = "EXECUTE ";
	private static final String DEALLOCATE = "DEALLOCATE ";
	private static final String PIN_KEY = "PIN KEY ";
	private static final String UNPIN_KEY = "UNPIN KEY ";
	private static final String TTL = " TTL ";
	private static final String QOS = " QOS ";
	private static final String SET_SCHEMA = "SET SCHEMA ";
	private static final String SET_REMOTE_DIRTY = "SET REMOTE_DIRTY ";
	private static final String TRUE = "TRUE";
	private static final String FALSE = "FALSE";

	private SqlClientSql() {
	}

	public record PrepareParts(String name, String body) {
	}

	public static PrepareParts tryParsePrepare(String sql) {
		if (sql == null || sql.isBlank()) {
			return null;
		}
		try {
			final String trimmed = sql.trim();
			final CharStream chars = CharStreams.fromString(trimmed);
			final SimplifiedSqlLexer lexer = new SimplifiedSqlLexer(chars);
			lexer.removeErrorListeners();
			final CommonTokenStream tokens = new CommonTokenStream(lexer);
			final SimplifiedSqlParser parser = new SimplifiedSqlParser(tokens);
			parser.removeErrorListeners();
			parser.setErrorHandler(new BailErrorStrategy());
			final SimplifiedSqlParser.ExecutableContext exec = parser.statement().executable();
			if (exec == null || exec.prepareStmt() == null) {
				return null;
			}
			final SimplifiedSqlParser.PrepareStmtContext prep = exec.prepareStmt();
			final String name = prep.ID().getText();
			final SimplifiedSqlParser.ExecutableContext bodyCtx = prep.executable();
			final String body = chars.getText(Interval.of(
					bodyCtx.getStart().getStartIndex(),
					bodyCtx.getStop().getStopIndex()));
			return new PrepareParts(name, body);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	public static String tryParseExecuteName(String sql) {
		if (sql == null || sql.isBlank()) {
			return null;
		}
		try {
			final SimplifiedSqlParser.ExecutableContext exec = parseExecutable(sql.trim());
			if (exec == null || exec.executeStmt() == null) {
				return null;
			}
			return exec.executeStmt().ID().getText();
		} catch (RuntimeException ex) {
			return null;
		}
	}

	public static boolean isDeallocate(String sql) {
		if (sql == null || sql.isBlank()) {
			return false;
		}
		try {
			final SimplifiedSqlParser.ExecutableContext exec = parseExecutable(sql.trim());
			return exec != null && exec.deallocateStmt() != null;
		} catch (RuntimeException ex) {
			return false;
		}
	}

	public static String tryParseDeallocateName(String sql) {
		if (sql == null || sql.isBlank()) {
			return null;
		}
		try {
			final SimplifiedSqlParser.ExecutableContext exec = parseExecutable(sql.trim());
			if (exec == null || exec.deallocateStmt() == null) {
				return null;
			}
			return exec.deallocateStmt().ID().getText();
		} catch (RuntimeException ex) {
			return null;
		}
	}

	public static String prepareSql(String name, String body) {
		return PREPARE + requireIdent(name) + AS + ObjectsRequireNonBlank(body, "body");
	}

	public static String executeSql(String name) {
		return EXECUTE + requireIdent(name);
	}

	public static String deallocateSql(String name) {
		return DEALLOCATE + requireIdent(name);
	}

	public static String pinSql(String table, Object key, Long ttlMsOrNull, String qosOrNull) {
		final StringBuilder sb = new StringBuilder(PIN_KEY);
		sb.append(requireIdent(table)).append(' ').append(literal(key));
		if (ttlMsOrNull != null) {
			sb.append(TTL).append(ttlMsOrNull.longValue());
		}
		if (qosOrNull != null && !qosOrNull.isBlank()) {
			sb.append(QOS).append(stringLiteral(qosOrNull));
		}
		return sb.toString();
	}

	public static String unpinSql(String table, Object key) {
		return UNPIN_KEY + requireIdent(table) + ' ' + literal(key);
	}

	public static String setSchemaSql(String schema) {
		return SET_SCHEMA + requireIdent(schema);
	}

	public static String setRemoteDirtySql(boolean enabled) {
		return SET_REMOTE_DIRTY + (enabled ? TRUE : FALSE);
	}

	public static String literal(Object value) {
        return switch (value) {
            case null -> "NULL";
            case Boolean b -> b ? TRUE : FALSE;
            case Number _ -> value.toString();
            default -> stringLiteral(String.valueOf(value));
        };
    }

	public static String stringLiteral(String raw) {
		return "'" + raw.replace("'", "''") + "'";
	}

	public static String requireIdent(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("identifier required");
		}
		final String trimmed = name.trim();
		final char first = trimmed.charAt(0);
		if (!(first == '_' || (first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z'))) {
			throw new IllegalArgumentException("invalid identifier: " + name);
		}
		for (int i = 1; i < trimmed.length(); i++) {
			final char c = trimmed.charAt(i);
			if (!(c == '_'
					|| (c >= 'A' && c <= 'Z')
					|| (c >= 'a' && c <= 'z')
					|| (c >= '0' && c <= '9'))) {
				throw new IllegalArgumentException("invalid identifier: " + name);
			}
		}
		return trimmed;
	}

	private static String ObjectsRequireNonBlank(String v, String label) {
		if (v == null || v.isBlank()) {
			throw new IllegalArgumentException(label + " required");
		}
		return v.trim();
	}

	private static SimplifiedSqlParser.ExecutableContext parseExecutable(String sql) {
		final SimplifiedSqlLexer lexer = new SimplifiedSqlLexer(CharStreams.fromString(sql));
		lexer.removeErrorListeners();
		final CommonTokenStream tokens = new CommonTokenStream(lexer);
		final SimplifiedSqlParser parser = new SimplifiedSqlParser(tokens);
		parser.removeErrorListeners();
		parser.setErrorHandler(new BailErrorStrategy());
		return parser.statement().executable();
	}
}
