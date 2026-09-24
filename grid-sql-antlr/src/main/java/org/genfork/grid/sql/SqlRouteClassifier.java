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

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.genfork.grid.antlr.SimplifiedSqlLexer;
import org.genfork.grid.antlr.SimplifiedSqlParser;

/**
 * ANTLR-only client/server route hint: read pool vs writer (no keyword sniff).
 * <p>
 * Server admission remains authoritative; a wrong client route is rejected on wire.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlRouteClassifier {
	private static final int PREPARE_NAME_CACHE_MAX = 256;

	private SqlRouteClassifier() {
	}

	/** Where to send the statement when dual-pool routing is enabled. */
	public enum Route {
		READ,
		WRITE
	}

	/**
	 * Classify SQL via SimplifiedSqlParser.statement().
	 * SELECT/EXPLAIN without FOR UPDATE -&gt; READ; bare SELECT with function calls,
	 * FOR UPDATE, and all other statements -&gt; WRITE. Unparseable SQL routes to WRITE.
	 */
	public static Route classify(String sql) {
		if (sql == null || sql.isBlank()) {
			throw new IllegalArgumentException("sql required");
		}
		try {
			final SimplifiedSqlLexer lexer = new SimplifiedSqlLexer(CharStreams.fromString(sql.trim()));
			lexer.removeErrorListeners();
			final CommonTokenStream tokens = new CommonTokenStream(lexer);
			final SimplifiedSqlParser parser = new SimplifiedSqlParser(tokens);
			parser.removeErrorListeners();
			parser.setErrorHandler(new BailErrorStrategy());
			final SimplifiedSqlParser.ExecutableContext exec = parser.statement().executable();
			return routeOfExecutable(exec);
		} catch (RuntimeException ex) {
			return Route.WRITE;
		}
	}

	/**
	 * Cache helper for PREPARE names: store the route of the prepared body.
	 */
	public static Route classifyPreparedBody(String preparedSql) {
		return classify(preparedSql);
	}

	static Route routeOfExecutable(SimplifiedSqlParser.ExecutableContext exec) {
		if (exec == null) {
			return Route.WRITE;
		}
		if (exec.explainStmt() != null) {
			return Route.READ;
		}
		if (exec.query() != null) {
			return routeOfQuery(exec.query());
		}
		if (exec.withQuery() != null) {
			return hasForUpdate(exec.withQuery()) ? Route.WRITE : Route.READ;
		}
		return Route.WRITE;
	}

	private static Route routeOfQuery(SimplifiedSqlParser.QueryContext query) {
		if (query == null) {
			return Route.WRITE;
		}
		if (hasForUpdate(query)) {
			return Route.WRITE;
		}
		if (query.selectExprQuery() != null && hasFunctionCall(query.selectExprQuery())) {
			return Route.WRITE;
		}
		return Route.READ;
	}

	private static boolean hasForUpdate(ParseTree tree) {
		if (tree == null) {
			return false;
		}
		if (tree instanceof SimplifiedSqlParser.ForUpdateClauseContext) {
			return true;
		}
		for (int i = 0; i < tree.getChildCount(); i++) {
			if (hasForUpdate(tree.getChild(i))) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasFunctionCall(ParseTree tree) {
		if (tree == null) {
			return false;
		}
		if (tree instanceof SimplifiedSqlParser.FunctionCallContext) {
			return true;
		}
		for (int i = 0; i < tree.getChildCount(); i++) {
			if (hasFunctionCall(tree.getChild(i))) {
				return true;
			}
		}
		return false;
	}

	/** Soft cap for optional prepare-name route caches (callers may ignore). */
	public static int prepareNameCacheMax() {
		return PREPARE_NAME_CACHE_MAX;
	}
}
