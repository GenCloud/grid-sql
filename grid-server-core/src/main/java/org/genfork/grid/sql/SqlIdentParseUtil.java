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

import org.genfork.grid.antlr.SimplifiedSqlParser.ColumnNameContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.FromItemContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.IdentContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.JoinClauseContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.JoinCondContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.JoinHeadContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.JoinTargetContext;
import org.genfork.grid.sql.ast.SelectAst.JoinEq;
import org.genfork.grid.sql.ast.SelectAst.JoinKind;

import java.util.ArrayList;
import java.util.List;

/**
 * ANTLR ident / join / from-alias extractors shared by statement and query parsers.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class SqlIdentParseUtil {
	private static final String FULL_TABLE_WHERE_SQL = "TRUE";

	private SqlIdentParseUtil() {
	}

	/** Full-table UPDATE residual when WHERE is omitted. */
	public static String fullTableWhereSql() {
		return FULL_TABLE_WHERE_SQL;
	}

	public static String identText(IdentContext ctx) {
		if (ctx == null) {
			throw new IllegalArgumentException("missing ident");
		}
		return ctx.getText();
	}

	/** Unqualified column id (t.col -> col). */
	public static String simpleColumn(ColumnNameContext ctx) {
		if (ctx == null || ctx.ident() == null || ctx.ident().isEmpty()) {
			throw new IllegalArgumentException("missing column name");
		}
		return identText(ctx.ident(ctx.ident().size() - 1));
	}

	/** Table qualifier when t.col; otherwise null. */
	public static String tableQualifier(ColumnNameContext ctx) {
		if (ctx == null || ctx.ident() == null || ctx.ident().size() < 2) {
			return null;
		}
		return identText(ctx.ident(0));
	}

	public static String fromItemAlias(FromItemContext from) {
		if (from == null) {
			return null;
		}
		if (from.aliasIdent != null) {
			return identText(from.aliasIdent);
		}
		if (from.aliasId != null) {
			return from.aliasId.getText();
		}
		if (from.alias != null) {
			return identText(from.alias);
		}
		return null;
	}

	public static String joinTargetAlias(JoinTargetContext target) {
		if (target == null) {
			return null;
		}
		if (target.aliasIdent != null) {
			return identText(target.aliasIdent);
		}
		if (target.aliasId != null) {
			return target.aliasId.getText();
		}
		return null;
	}

	public static String joinTableName(JoinClauseContext jc) {
		return jc.joinTarget().tableName().getText();
	}

	public static JoinKind joinKindOf(JoinClauseContext jc) {
		final JoinHeadContext head = jc.joinHead();
		if (head.FULL() != null) {
			return JoinKind.FULL;
		}
		if (head.RIGHT() != null) {
			return JoinKind.RIGHT;
		}
		if (head.LEFT() != null) {
			return JoinKind.LEFT;
		}
		return JoinKind.INNER;
	}

	public static List<JoinEq> joinEqs(JoinCondContext cond) {
		final List<ColumnNameContext> cols = cond.columnName();
		if (cols == null || cols.size() < 2 || (cols.size() % 2) != 0) {
			throw new IllegalArgumentException("JOIN ON requires column=column pairs");
		}
		final List<JoinEq> eqs = new ArrayList<>(cols.size() / 2);
		for (int i = 0; i < cols.size(); i += 2) {
			eqs.add(new JoinEq(simpleColumn(cols.get(i)), simpleColumn(cols.get(i + 1))));
		}
		return List.copyOf(eqs);
	}

	/** Null-safe textOf for optional rule contexts. */
	public static String textOfOrNull(org.antlr.v4.runtime.ParserRuleContext ctx) {
		if (ctx == null || ctx.getStart() == null || ctx.getStop() == null) {
			return null;
		}
		return SqlParseSupport.textOf(ctx);
	}
}