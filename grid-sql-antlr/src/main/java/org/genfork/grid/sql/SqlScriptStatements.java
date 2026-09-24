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

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.genfork.grid.antlr.SimplifiedSqlLexer;
import org.genfork.grid.antlr.SimplifiedSqlParser;

/**
 * ANTLR-only multi-statement script split for JDBC tooling ({@code script} rule).
 * <p>
 * Extracts each {@code executable} text from the char-stream interval (preserves spaces
 * inside statements; does not hand-roll {@code ;} lexing).
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class SqlScriptStatements {
	private static final String ERR_SQL_REQUIRED = "sql required";
	private static final String ERR_BAD_SCRIPT = "Bad SQL script: ";

	private SqlScriptStatements() {
	}

	/**
	 * Split a DBeaver-style script into executable SQL strings (no trailing semicolons).
	 *
	 * @return empty list when {@code sql} is null/blank; otherwise one or more parts
	 * @throws IllegalArgumentException when the input is not a valid {@code script}
	 */
	public static List<String> splitExecutables(String sql) {
		if (sql == null || sql.isBlank()) {
			return List.of();
		}
		final String trimmed = sql.trim();
		try {
			final CharStream chars = CharStreams.fromString(trimmed);
			final SimplifiedSqlLexer lexer = new SimplifiedSqlLexer(chars);
			lexer.removeErrorListeners();
			final CommonTokenStream tokens = new CommonTokenStream(lexer);
			final SimplifiedSqlParser parser = new SimplifiedSqlParser(tokens);
			parser.removeErrorListeners();
			parser.setErrorHandler(new BailErrorStrategy());
			final SimplifiedSqlParser.ScriptContext script = parser.script();
			final List<SimplifiedSqlParser.ExecutableContext> execs = script.executable();
			final List<String> parts = new ArrayList<>(execs.size());
			for (SimplifiedSqlParser.ExecutableContext exec : execs) {
				if (exec == null || exec.getStart() == null || exec.getStop() == null) {
					continue;
				}
				final int start = exec.getStart().getStartIndex();
				final int stop = exec.getStop().getStopIndex();
				parts.add(chars.getText(Interval.of(start, stop)));
			}
			if (parts.isEmpty()) {
				throw new IllegalArgumentException(ERR_BAD_SCRIPT + "no executable statements");
			}
			return List.copyOf(parts);
		} catch (ParseCancellationException ex) {
			throw new IllegalArgumentException(ERR_BAD_SCRIPT + trimmed, ex);
		} catch (RuntimeException ex) {
			if (ex instanceof IllegalArgumentException) {
				throw ex;
			}
			throw new IllegalArgumentException(ERR_BAD_SCRIPT + trimmed, ex);
		}
	}

	/**
	 * @throws IllegalArgumentException when {@code sql} is null/blank
	 */
	public static void requireSql(String sql) {
		if (sql == null || sql.isBlank()) {
			throw new IllegalArgumentException(ERR_SQL_REQUIRED);
		}
	}
}
