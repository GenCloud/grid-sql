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

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.misc.Interval;
import org.genfork.grid.antlr.SimplifiedSqlParser;
import org.genfork.grid.antlr.SimplifiedSqlParser.ValueContext;
import org.genfork.grid.catalog.SqlTypeCoercion;
import org.genfork.grid.sql.ast.DmlAst.SequenceCallExpr;

/**
 * Shared literal / text helpers for ANTLR SQL parsing.
 * <p>
 * Context-to-AST builders stay in {@link SqlStatementParser}; this type owns zone/bind
 * thread-locals and value decoding used across parse helpers.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlParseSupport {
	private static final ThreadLocal<ZoneId> PARSE_ZONE = ThreadLocal.withInitial(() -> ZoneOffset.UTC);

	/** When set, {@code ?} literals become {@link SqlBindParam} (PREPARE body only). */
	private static final ThreadLocal<int[]> PREPARE_BIND_COUNTER = new ThreadLocal<>();

	/** When true, {@code OLD.col}/{@code NEW.col} become {@link SqlTriggerRowRef}. */
	private static final ThreadLocal<Boolean> TRIGGER_PARSE = new ThreadLocal<>();

	private SqlParseSupport() {
	}

	/**
	 * Install parse timezone for the duration of one {@link SqlStatementParser#parse} call.
	 */
	static void beginParse(ZoneId zone) {
		PARSE_ZONE.set(SqlTypeCoercion.zoneOrUtc(zone));
	}

	/**
	 * Install parse timezone and bind-index counter for {@link SqlStatementParser#parsePreparedBody}.
	 */
	static void beginPrepareParse(ZoneId zone) {
		PARSE_ZONE.set(SqlTypeCoercion.zoneOrUtc(zone));
		PREPARE_BIND_COUNTER.set(new int[]{0});
	}

	/**
	 * Install parse timezone and enable OLD/NEW → {@link SqlTriggerRowRef} for trigger body/WHEN.
	 */
	static void beginTriggerParse(ZoneId zone) {
		PARSE_ZONE.set(SqlTypeCoercion.zoneOrUtc(zone));
		TRIGGER_PARSE.set(Boolean.TRUE);
	}

	/** Clear zone / bind thread-locals after parse. */
	static void endParse() {
		PREPARE_BIND_COUNTER.remove();
		TRIGGER_PARSE.remove();
		PARSE_ZONE.remove();
	}

	static boolean triggerParseActive() {
		return Boolean.TRUE.equals(TRIGGER_PARSE.get());
	}

	/** Exact input text covered by a parser rule context. */
	public static String textOf(ParserRuleContext ctx) {
		if (ctx == null || ctx.getStart() == null || ctx.getStop() == null) {
			throw new IllegalArgumentException("missing parse context text");
		}
		final int a = ctx.getStart().getStartIndex();
		final int b = ctx.getStop().getStopIndex();
		return ctx.getStart().getInputStream().getText(Interval.of(a, b));
	}

	/** Decode a grammar {@code value} into a Java literal / bind / sequence call. */
	public static Object literal(ValueContext v) {
		if (v.oldNewRef() != null) {
			if (!triggerParseActive()) {
				throw new IllegalArgumentException("OLD/NEW only valid in trigger body or WHEN");
			}
			final SimplifiedSqlParser.OldNewRefContext ref = v.oldNewRef();
			final boolean oldRow = ref.OLD() != null;
			return new SqlTriggerRowRef(oldRow, ref.ID().getText());
		}
		if (v.PARAM() != null) {
			final int[] counter = PREPARE_BIND_COUNTER.get();
			if (counter == null) {
				throw new IllegalArgumentException("unresolved bind placeholder ?");
			}
			return new SqlBindParam(counter[0]++);
		}
		if (v.sequenceCall() != null) {
			return sequenceCallExpr(v.sequenceCall());
		}
		if (v.CAST() != null) {
			return castLiteral(literal(v.value()), v.typeName().getText());
		}
		if (v.UUID_TYPE() != null && v.STRING() != null) {
			return SqlTypeCoercion.toUuid(unquote(v.STRING().getText()));
		}
		if (v.DATE_TYPE() != null && v.STRING() != null) {
			return SqlTypeCoercion.toDate(unquote(v.STRING().getText()));
		}
		if (v.TIME_TYPE() != null && v.STRING() != null) {
			return SqlTypeCoercion.toTime(unquote(v.STRING().getText()));
		}
		if (v.TIMESTAMP_TYPE() != null && v.STRING() != null) {
			return SqlTypeCoercion.toTimestampString(unquote(v.STRING().getText()));
		}
		if (v.TIMESTAMPTZ_TYPE() != null && v.STRING() != null) {
			return SqlTypeCoercion.toTimestamptz(unquote(v.STRING().getText()), PARSE_ZONE.get());
		}
		if (v.caseExpr() != null) {
			return evalConstantCase(v.caseExpr());
		}
		if (v.NULL() != null) {
			return null;
		}
		if (v.TRUE() != null) {
			return Boolean.TRUE;
		}
		if (v.FALSE() != null) {
			return Boolean.FALSE;
		}
		if (v.STRING() != null) {
			return unquote(v.STRING().getText());
		}
		if (v.FLOAT() != null) {
			return Double.valueOf(v.getText());
		}
		if (v.INT() != null) {
			final String t = v.getText();
			try {
				return Integer.valueOf(t);
			} catch (NumberFormatException e) {
				return Long.valueOf(t);
			}
		}
		throw new IllegalArgumentException("unsupported literal: " + v.getText());
	}

	/** Cast a decoded literal to a SQL type token. */
	public static Object castLiteral(Object value, String typeToken) {
		if (value == null) {
			return null;
		}
		final String t = typeToken.trim().toUpperCase(Locale.ROOT);
		return switch (t) {
			case "INT", "INTEGER" -> value instanceof Number n
					? Integer.valueOf(n.intValue())
					: Integer.valueOf(String.valueOf(value));
			case "BIGINT", "LONG" -> value instanceof Number n
					? Long.valueOf(n.longValue())
					: Long.valueOf(String.valueOf(value));
			case "DOUBLE", "FLOAT", "REAL" -> value instanceof Number n
					? Double.valueOf(n.doubleValue())
					: Double.valueOf(String.valueOf(value));
			case "BOOLEAN", "BOOL" -> value instanceof Boolean b
					? b
					: Boolean.valueOf(String.valueOf(value));
			case "VARCHAR", "STRING", "TEXT" -> String.valueOf(value);
			case "UUID" -> SqlTypeCoercion.toUuid(value);
			case "DATE" -> SqlTypeCoercion.toDate(value);
			case "TIME" -> SqlTypeCoercion.toTime(value);
			case "TIMESTAMP", "DATETIME" -> SqlTypeCoercion.toTimestampString(value);
			case "TIMESTAMPTZ" -> SqlTypeCoercion.toTimestamptz(value, PARSE_ZONE.get());
			default -> throw new IllegalArgumentException("Unsupported CAST type: " + typeToken);
		};
	}

	/** String form of a value context (empty string for SQL NULL). */
	public static String stringLit(ValueContext v) {
		final Object o = literal(v);
		return o == null ? "" : String.valueOf(o);
	}

	/** Strip surrounding single quotes and unescape doubled quotes. */
	public static String unquote(String raw) {
		if (raw == null) {
			return null;
		}
		if (raw.length() >= 2 && raw.charAt(0) == '\'' && raw.charAt(raw.length() - 1) == '\'') {
			return raw.substring(1, raw.length() - 1).replace("''", "'");
		}
		return raw;
	}

	/** Resolve {@code nextval}/{@code currval} argument to a sequence name. */
	public static String sequenceNameArg(SimplifiedSqlParser.SequenceNameArgContext ctx) {
		if (ctx.STRING() != null) {
			return unquote(ctx.STRING().getText());
		}
		return ctx.ID().getText();
	}

	/** Build {@link SequenceCallExpr} from a {@code sequenceCall} rule. */
	public static SequenceCallExpr sequenceCallExpr(SimplifiedSqlParser.SequenceCallContext ctx) {
		final String name = sequenceNameArg(ctx.sequenceNameArg());
		return new SequenceCallExpr(ctx.NEXTVAL() != null, name);
	}

	private static Object evalConstantCase(SimplifiedSqlParser.CaseExprContext ctx) {
		final List<SimplifiedSqlParser.ExpressionContext> whens = ctx.expression();
		final List<ValueContext> values = ctx.value();
		final boolean hasElse = ctx.ELSE() != null;
		final int branchCount = hasElse ? values.size() - 1 : values.size();
		if (whens.size() != branchCount) {
			throw new IllegalArgumentException("CASE WHEN/THEN arity mismatch");
		}
		for (int i = 0; i < whens.size(); i++) {
			final Boolean cond = constantBool(whens.get(i));
			if (cond == null) {
				throw new IllegalArgumentException(
						"non-constant CASE WHEN not supported in value context (use TRUE/FALSE only)");
			}
			if (cond) {
				return literal(values.get(i));
			}
		}
		if (hasElse) {
			return literal(values.getLast());
		}
		return null;
	}

	private static Boolean constantBool(SimplifiedSqlParser.ExpressionContext expr) {
		if (expr instanceof SimplifiedSqlParser.TrueOrFalseExpressionContext tf) {
			final SimplifiedSqlParser.TrueFalseExpressionContext inner = tf.trueFalseExpression();
			if (inner == null) {
				return null;
			}
			if (inner.TRUE() != null) {
				return Boolean.TRUE;
			}
			if (inner.FALSE() != null) {
				return Boolean.FALSE;
			}
			return null;
		}
		if (expr instanceof SimplifiedSqlParser.ParenExpressionContext paren) {
			return constantBool(paren.expression());
		}
		return null;
	}
}
