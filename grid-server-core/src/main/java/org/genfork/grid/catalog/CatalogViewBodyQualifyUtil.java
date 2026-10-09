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
package org.genfork.grid.catalog;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.genfork.grid.antlr.SimplifiedSqlLexer;
import org.genfork.grid.antlr.SimplifiedSqlParser;
import org.genfork.grid.antlr.SimplifiedSqlParser.CteDefContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.FromItemContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.JoinClauseContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.JoinTargetContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.QueryContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.SelectQueryContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.TableNameContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.UnionTailContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.WithQueryContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Session-invariant VIEW / MV body rewrite: bare {@code FROM}/{@code JOIN} tables become
 * {@link CatalogQualifiedName#catalogKey()} under the view's schema (same contract as FK parents).
 * <p>
 * Call only on DDL / catalog-register edges ({@link TableCatalog#createView}). Never from
 * {@code viewSelectBodies} or {@link org.genfork.grid.sql.SqlNamedQueryExpand}. Already-qualified
 * refs are left unchanged.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
public final class CatalogViewBodyQualifyUtil {
	private static final char QUALIFIER_SEP = '.';
	private static final String WITH_KW = "WITH";

	private CatalogViewBodyQualifyUtil() {
	}

	/**
	 * Qualify unqualified table refs in a VIEW defining SELECT using {@code viewSchemaName}.
	 *
	 * @return rewritten SQL, or the original when unchanged / blank
	 */
	public static String qualifyUnqualifiedTables(String selectSql, String viewSchemaName) {
		Objects.requireNonNull(selectSql, "selectSql");
		Objects.requireNonNull(viewSchemaName, "viewSchemaName");
		if (selectSql.isBlank()) {
			return selectSql;
		}
		final String schema = viewSchemaName.toLowerCase(Locale.ROOT);
		final SimplifiedSqlLexer lexer = new SimplifiedSqlLexer(CharStreams.fromString(selectSql));
		lexer.removeErrorListeners();
		final CommonTokenStream tokens = new CommonTokenStream(lexer);
		final SimplifiedSqlParser parser = new SimplifiedSqlParser(tokens);
		parser.removeErrorListeners();
		final List<Replacement> reps = new ArrayList<>();
		if (startsWithWith(selectSql)) {
			final WithQueryContext with = parser.withQuery();
			for (CteDefContext cte : with.cteDef()) {
				collectQuery(cte.query(), reps, schema);
			}
			collectQuery(with.query(), reps, schema);
		} else {
			collectQuery(parser.query(), reps, schema);
		}
		return apply(selectSql, reps);
	}

	private static boolean startsWithWith(String sql) {
		int i = 0;
		while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
			i++;
		}
		return sql.regionMatches(true, i, WITH_KW, 0, WITH_KW.length());
	}

	private static void collectQuery(QueryContext query, List<Replacement> reps, String schema) {
		if (query == null) {
			return;
		}
		if (query.selectQuery() != null) {
			collectSelect(query.selectQuery(), reps, schema);
		}
		if (query.unionTail() != null) {
			for (UnionTailContext tail : query.unionTail()) {
				if (tail.selectQuery() != null) {
					collectSelect(tail.selectQuery(), reps, schema);
				}
			}
		}
	}

	private static void collectSelect(SelectQueryContext select, List<Replacement> reps, String schema) {
		if (select == null) {
			return;
		}
		final FromItemContext from = select.fromItem();
		if (from != null && from.tableName() != null) {
			maybeQualify(from.tableName(), reps, schema);
		}
		if (select.joinClause() != null) {
			for (JoinClauseContext jc : select.joinClause()) {
				final JoinTargetContext target = jc.joinTarget();
				if (target != null && target.tableName() != null) {
					maybeQualify(target.tableName(), reps, schema);
				}
			}
		}
		walkNestedQueries(select, reps, schema);
	}

	private static void walkNestedQueries(ParseTree node, List<Replacement> reps, String schema) {
		if (node == null) {
			return;
		}
		for (int i = 0; i < node.getChildCount(); i++) {
			final ParseTree child = node.getChild(i);
			if (child instanceof SelectQueryContext nested && nested != node) {
				collectSelect(nested, reps, schema);
			} else if (child instanceof QueryContext q) {
				collectQuery(q, reps, schema);
			} else {
				walkNestedQueries(child, reps, schema);
			}
		}
	}

	private static void maybeQualify(TableNameContext tableName, List<Replacement> reps, String schema) {
		if (tableName == null || tableName.start == null || tableName.stop == null) {
			return;
		}
		final String raw = tableName.getText();
		if (raw == null || raw.isBlank() || raw.indexOf(QUALIFIER_SEP) >= 0) {
			return;
		}
		final String qualified = CatalogQualifiedName.of(schema, raw).catalogKey();
		if (qualified.equalsIgnoreCase(raw)) {
			return;
		}
		reps.add(new Replacement(
				tableName.start.getStartIndex(),
				tableName.stop.getStopIndex(),
				qualified));
	}

	private static String apply(String original, List<Replacement> reps) {
		if (reps.isEmpty()) {
			return original;
		}
		reps.sort(Comparator.comparingInt(Replacement::start).reversed());
		final StringBuilder sb = new StringBuilder(original);
		for (Replacement rep : reps) {
			sb.replace(rep.start(), rep.stop() + 1, rep.text());
		}
		return sb.toString();
	}

	private record Replacement(int start, int stop, String text) {
	}
}
