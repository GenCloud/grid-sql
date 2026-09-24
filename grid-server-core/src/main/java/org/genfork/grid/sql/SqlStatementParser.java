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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.genfork.grid.antlr.SimplifiedSqlLexer;
import org.genfork.grid.antlr.SimplifiedSqlParser;
import org.genfork.grid.antlr.SimplifiedSqlParser.AlterUserStmtContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.ColumnDefContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.ColumnNameContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.CreateIndexStmtContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.CreateTableStmtContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.CreateUserStmtContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.DeleteStmtContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.DropIndexStmtContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.DropTableStmtContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.GrantStmtContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.InsertStmtContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.PrivilegeNameContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.QueryContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.RevokeStmtContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.SelectListContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.SetOperatorContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.StatementContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.TableElementContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.UpdateAssignContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.UpdateRhsContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.UpdateStmtContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.ValueContext;
import org.genfork.grid.antlr.SimplifiedSqlParser.ValueTupleContext;
import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.SqlPrivilege;
import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.query.plan.UpdatePlan;
import org.genfork.grid.query.util.SetOpKind;
import org.genfork.grid.replication.codec.ModifyPayload;
import org.genfork.grid.sql.ast.AdminAst.AlterUserPasswordSql;
import org.genfork.grid.sql.ast.AdminAst.CreateRoleSql;
import org.genfork.grid.sql.ast.AdminAst.CreateUserSql;
import org.genfork.grid.sql.ast.AdminAst.DropRoleSql;
import org.genfork.grid.sql.ast.AdminAst.DropUserSql;
import org.genfork.grid.sql.ast.AdminAst.GrantRoleMembershipSql;
import org.genfork.grid.sql.ast.AdminAst.GrantSql;
import org.genfork.grid.sql.ast.AdminAst.GrantToRoleSql;
import org.genfork.grid.sql.ast.AdminAst.PinSql;
import org.genfork.grid.sql.ast.AdminAst.RevokeSql;
import org.genfork.grid.sql.ast.AdminAst.UnpinSql;
import org.genfork.grid.sql.ast.DdlAst.AlterTableSql;
import org.genfork.grid.sql.ast.DdlAst.CheckSpec;
import org.genfork.grid.sql.ast.DdlAst.ColumnSpec;
import org.genfork.grid.sql.ast.DdlAst.CreateFunctionSql;
import org.genfork.grid.sql.ast.DdlAst.CreateTriggerSql;
import org.genfork.grid.sql.ast.DdlAst.CreateIndexSql;
import org.genfork.grid.sql.ast.DdlAst.CreateMaterializedViewSql;
import org.genfork.grid.sql.ast.DdlAst.CreateSchemaSql;
import org.genfork.grid.sql.ast.DdlAst.CreateSequenceSql;
import org.genfork.grid.sql.ast.DdlAst.CreateTableSql;
import org.genfork.grid.sql.ast.DdlAst.CreateViewSql;
import org.genfork.grid.sql.ast.DdlAst.DropFunctionSql;
import org.genfork.grid.sql.ast.DdlAst.DropTriggerSql;
import org.genfork.grid.sql.ast.DdlAst.DropIndexSql;
import org.genfork.grid.sql.ast.DdlAst.DropSchemaSql;
import org.genfork.grid.sql.ast.DdlAst.DropSequenceSql;
import org.genfork.grid.sql.ast.DdlAst.DropTableSql;
import org.genfork.grid.sql.ast.DdlAst.DropViewSql;
import org.genfork.grid.sql.ast.DdlAst.FkSpec;
import org.genfork.grid.sql.ast.DdlAst.RefreshMaterializedViewSql;
import org.genfork.grid.sql.ast.DdlAst.SequenceValueSql;
import org.genfork.grid.sql.ast.DdlAst.SetRemoteDirtySql;
import org.genfork.grid.sql.ast.DdlAst.SetSchemaSql;
import org.genfork.grid.sql.ast.DmlAst.AnalyzeSql;
import org.genfork.grid.sql.ast.DmlAst.ConflictAction;
import org.genfork.grid.sql.ast.DmlAst.DeleteSql;
import org.genfork.grid.sql.ast.DmlAst.InsertSql;
import org.genfork.grid.sql.ast.DmlAst.MergeSql;
import org.genfork.grid.sql.ast.DmlAst.OnConflict;
import org.genfork.grid.sql.ast.DmlAst.SequenceCallExpr;
import org.genfork.grid.sql.ast.DmlAst.UpdateSql;
import org.genfork.grid.sql.ast.SelectAst.AggregateSelectItem;
import org.genfork.grid.sql.ast.SelectAst.ColumnFuncArg;
import org.genfork.grid.sql.ast.SelectAst.ColumnSelectItem;
import org.genfork.grid.sql.ast.SelectAst.ExplainSql;
import org.genfork.grid.sql.ast.SelectAst.FuncArg;
import org.genfork.grid.sql.ast.SelectAst.FunctionFrom;
import org.genfork.grid.sql.ast.SelectAst.FunctionSelectItem;
import org.genfork.grid.sql.ast.SelectAst.HavingPredicate;
import org.genfork.grid.sql.ast.SelectAst.JoinEdge;
import org.genfork.grid.sql.ast.SelectAst.JoinKind;
import org.genfork.grid.sql.ast.SelectAst.LiteralFuncArg;
import org.genfork.grid.sql.ast.SelectAst.RecursiveCteSql;
import org.genfork.grid.sql.ast.SelectAst.SelectItem;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.genfork.grid.sql.ast.SelectAst.SetOpSql;
import org.genfork.grid.sql.ast.SelectAst.WhereSubquery;
import org.genfork.grid.sql.ast.SelectAst.WindowSelectItem;
import org.genfork.grid.sql.ast.Stmt;
import org.genfork.grid.sql.ast.TxAst.BeginSql;
import org.genfork.grid.sql.ast.TxAst.CommitSql;
import org.genfork.grid.sql.ast.TxAst.DeallocateSql;
import org.genfork.grid.sql.ast.TxAst.ExecuteSql;
import org.genfork.grid.sql.ast.TxAst.PrepareSql;
import org.genfork.grid.sql.ast.TxAst.ReleaseSavepointSql;
import org.genfork.grid.sql.ast.TxAst.RollbackSql;
import org.genfork.grid.sql.ast.TxAst.RollbackToSavepointSql;
import org.genfork.grid.sql.ast.TxAst.SavepointSql;

/**
 * ANTLR front-end for {@link SqlEngine}: parses {@code statement} into typed plans.
 * SELECT text is re-executed via {@link org.genfork.grid.query.plan.QueryParser} / index path.
 * AST types live in {@link org.genfork.grid.sql.ast}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlStatementParser {
	private SqlStatementParser() {
	}

	public static Stmt parse(String sql) {
		return parse(sql, ZoneOffset.UTC);
	}

	public static Stmt parse(String sql, ZoneId zone) {
		if (sql == null || sql.isBlank()) {
			throw new IllegalArgumentException("SQL is empty");
		}
		SqlParseSupport.beginParse(zone);
		try {
			return parseStrict(sql.trim());
		} catch (ParseCancellationException ex) {
			throw new IllegalArgumentException("Bad SQL: " + sql, ex);
		} finally {
			SqlParseSupport.endParse();
		}
	}

	/**
	 * ANTLR-parse a PREPARE body, capturing {@code ?} as {@link SqlBindParam} for later EXECUTE bind.
	 */
	public static Stmt parsePreparedBody(String sql, ZoneId zone) {
		if (sql == null || sql.isBlank()) {
			throw new IllegalArgumentException("SQL is empty");
		}
		SqlParseSupport.beginPrepareParse(zone);
		try {
			return parseStrict(sql.trim());
		} catch (ParseCancellationException ex) {
			throw new IllegalArgumentException("Bad SQL: " + sql, ex);
		} finally {
			SqlParseSupport.endParse();
		}
	}

	/**
	 * ANTLR-parse a trigger body, capturing {@code OLD.col}/{@code NEW.col} as {@link SqlTriggerRowRef}.
	 */
	public static Stmt parseTriggerBody(String sql, ZoneId zone) {
		if (sql == null || sql.isBlank()) {
			throw new IllegalArgumentException("SQL is empty");
		}
		SqlParseSupport.beginTriggerParse(zone);
		try {
			return parseStrict(sql.trim());
		} catch (ParseCancellationException ex) {
			throw new IllegalArgumentException("Bad trigger body SQL: " + sql, ex);
		} finally {
			SqlParseSupport.endParse();
		}
	}

	private static Stmt parseStrict(String sql) {
		final SimplifiedSqlLexer lexer = new SimplifiedSqlLexer(CharStreams.fromString(sql));
		lexer.removeErrorListeners();
		final CommonTokenStream tokens = new CommonTokenStream(lexer);
		final SimplifiedSqlParser parser = new SimplifiedSqlParser(tokens);
		parser.removeErrorListeners();
		parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
		parser.setErrorHandler(new BailErrorStrategy());
		StatementContext stmt;
		try {
			stmt = parser.statement();
		} catch (ParseCancellationException ex) {
			tokens.seek(0);
			parser.reset();
			parser.getInterpreter().setPredictionMode(PredictionMode.LL);
			parser.setErrorHandler(new BailErrorStrategy());
			stmt = parser.statement();
		}
		return fromExecutable(stmt.executable(), tokens, sql);
	}

	private static Stmt fromExecutable(
			SimplifiedSqlParser.ExecutableContext ex,
			CommonTokenStream tokens,
			String fullSql
	) {
		if (ex.beginStmt() != null) {
			return new BeginSql();
		}
		if (ex.commitStmt() != null) {
			return new CommitSql();
		}
		if (ex.rollbackStmt() != null) {
			return new RollbackSql();
		}
		if (ex.savepointStmt() != null) {
			return new SavepointSql(ex.savepointStmt().ID().getText());
		}
		if (ex.rollbackToSavepointStmt() != null) {
			return new RollbackToSavepointSql(ex.rollbackToSavepointStmt().ID().getText());
		}
		if (ex.releaseSavepointStmt() != null) {
			return new ReleaseSavepointSql(ex.releaseSavepointStmt().ID().getText());
		}
		if (ex.prepareStmt() != null) {
			return parsePrepare(ex.prepareStmt(), tokens);
		}
		if (ex.executeStmt() != null) {
			return parseExecute(ex.executeStmt());
		}
		if (ex.deallocateStmt() != null) {
			return new DeallocateSql(ex.deallocateStmt().ID().getText());
		}
		if (ex.createUserStmt() != null) {
			final CreateUserStmtContext value = ex.createUserStmt();
			return new CreateUserSql(value.ID().getText(), SqlParseSupport.unquote(value.STRING().getText()));
		}
		if (ex.dropUserStmt() != null) {
			return new DropUserSql(ex.dropUserStmt().ID().getText());
		}
		if (ex.alterUserStmt() != null) {
			final AlterUserStmtContext value = ex.alterUserStmt();
			return new AlterUserPasswordSql(value.ID().getText(), SqlParseSupport.unquote(value.STRING().getText()));
		}
		if (ex.createRoleStmt() != null) {
			return new CreateRoleSql(ex.createRoleStmt().ID().getText());
		}
		if (ex.dropRoleStmt() != null) {
			return new DropRoleSql(ex.dropRoleStmt().ID().getText());
		}
		if (ex.grantStmt() != null) {
			return parseGrant(ex.grantStmt());
		}
		if (ex.revokeStmt() != null) {
			return parseRevoke(ex.revokeStmt());
		}
		if (ex.query() != null) {
			return parseQuery(ex.query(), fullSql);
		}
		if (ex.withQuery() != null) {
			final SimplifiedSqlParser.WithQueryContext wq = ex.withQuery();
			if (wq.RECURSIVE() != null) {
				return parseRecursiveCte(wq, fullSql);
			}
			final QueryContext q = wq.query();
			return parseQuery(q, SqlParseSupport.textOf(q));
		}
		if (ex.explainStmt() != null) {
			final SimplifiedSqlParser.ExplainStmtContext explainCtx = ex.explainStmt();
			final boolean analyze = explainCtx.ANALYZE() != null;
			final ParserRuleContext body = explainCtx.explainBody();
			final int start = body.start.getStartIndex();
			final int stop = body.stop.getStopIndex();
			final String bodySql = tokens.getTokenSource().getInputStream()
					.getText(Interval.of(start, stop));
			return new ExplainSql(parseStrict(bodySql), analyze);
		}
		if (ex.insertStmt() != null) {
			return parseInsert(ex.insertStmt());
		}
		if (ex.mergeStmt() != null) {
			return parseMerge(ex.mergeStmt());
		}
		if (ex.analyzeStmt() != null) {
			return new AnalyzeSql(ex.analyzeStmt().tableName().getText());
		}
		if (ex.deleteStmt() != null) {
			return parseDelete(ex.deleteStmt());
		}
		if (ex.updateStmt() != null) {
			return parseUpdate(ex.updateStmt());
		}
		if (ex.createTableStmt() != null) {
			return parseCreateTable(ex.createTableStmt());
		}
		if (ex.dropTableStmt() != null) {
			return parseDropTable(ex.dropTableStmt());
		}
		if (ex.createViewStmt() != null) {
			return parseCreateView(ex.createViewStmt());
		}
		if (ex.dropViewStmt() != null) {
			return parseDropView(ex.dropViewStmt());
		}
		if (ex.createMaterializedViewStmt() != null) {
			return parseCreateMaterializedView(ex.createMaterializedViewStmt());
		}
		if (ex.refreshMaterializedViewStmt() != null) {
			return new RefreshMaterializedViewSql(
					ex.refreshMaterializedViewStmt().tableName().getText());
		}
		if (ex.createFunctionStmt() != null) {
			return parseCreateFunction(ex.createFunctionStmt());
		}
		if (ex.dropFunctionStmt() != null) {
			final SimplifiedSqlParser.DropFunctionStmtContext df = ex.dropFunctionStmt();
			return new DropFunctionSql(df.ID().getText(), df.IF() != null);
		}
		if (ex.createTriggerStmt() != null) {
			return parseCreateTrigger(ex.createTriggerStmt());
		}
		if (ex.dropTriggerStmt() != null) {
			return parseDropTrigger(ex.dropTriggerStmt());
		}
		if (ex.createIndexStmt() != null) {
			return parseCreateIndex(ex.createIndexStmt());
		}
		if (ex.dropIndexStmt() != null) {
			return parseDropIndex(ex.dropIndexStmt());
		}
		if (ex.createSchemaStmt() != null) {
			final SimplifiedSqlParser.CreateSchemaStmtContext cs = ex.createSchemaStmt();
			return new CreateSchemaSql(cs.ID().getText(), cs.IF() != null);
		}
		if (ex.dropSchemaStmt() != null) {
			final SimplifiedSqlParser.DropSchemaStmtContext ds = ex.dropSchemaStmt();
			return new DropSchemaSql(ds.ID().getText(), ds.IF() != null);
		}
		if (ex.setSchemaStmt() != null) {
			return new SetSchemaSql(ex.setSchemaStmt().ID().getText());
		}
		if (ex.setRemoteDirtyStmt() != null) {
			return new SetRemoteDirtySql(ex.setRemoteDirtyStmt().trueFalseExpression().TRUE() != null);
		}
		if (ex.alterTableStmt() != null) {
			return parseAlterTable(ex.alterTableStmt());
		}
		if (ex.pinStmt() != null) {
			return parsePin(ex.pinStmt());
		}
		if (ex.unpinStmt() != null) {
			return parseUnpin(ex.unpinStmt());
		}
		if (ex.createSequenceStmt() != null) {
			return parseCreateSequence(ex.createSequenceStmt());
		}
		if (ex.dropSequenceStmt() != null) {
			return parseDropSequence(ex.dropSequenceStmt());
		}
		if (ex.selectSequenceStmt() != null) {
			return parseSelectSequence(ex.selectSequenceStmt());
		}
		throw new IllegalArgumentException("Unsupported SQL: " + fullSql);
	}

	private static Stmt parseGrant(GrantStmtContext context) {
		if (context.privilegeList() == null) {
			return new GrantRoleMembershipSql(context.ID(0).getText(), context.ID(1).getText());
		}
		final boolean schemaScope = context.privilegeTarget().SCHEMA() != null;
		final String target = schemaScope
				? context.privilegeTarget().ID().getText()
				: context.privilegeTarget().tableName().getText();
		final Set<SqlPrivilege> privileges = parsePrivileges(context.privilegeList().privilegeName());
		if (context.ROLE() != null) {
			return new GrantToRoleSql(context.ID(0).getText(), schemaScope, target, privileges);
		}
		return new GrantSql(context.ID(0).getText(), schemaScope, target, privileges);
	}

	private static RevokeSql parseRevoke(RevokeStmtContext context) {
		final boolean schemaScope = context.privilegeTarget().SCHEMA() != null;
		final String target = schemaScope
				? context.privilegeTarget().ID().getText()
				: context.privilegeTarget().tableName().getText();
		return new RevokeSql(context.ID().getText(), schemaScope, target,
				parsePrivileges(context.privilegeList().privilegeName()));
	}

	private static Set<SqlPrivilege> parsePrivileges(
			List<PrivilegeNameContext> contexts
	) {
		final EnumSet<SqlPrivilege> privileges = EnumSet.noneOf(SqlPrivilege.class);
		for (PrivilegeNameContext context : contexts) {
			privileges.add(SqlPrivilege.valueOf(context.getText().toUpperCase(Locale.ROOT)));
		}
		return Set.copyOf(privileges);
	}

	private static PinSql parsePin(SimplifiedSqlParser.PinStmtContext ctx) {
		final String table = ctx.tableName().getText();
		final Object keyLit = SqlParseSupport.literal(ctx.value());
		Long ttl = null;
		if (ctx.TTL() != null && ctx.INT() != null) {
			ttl = Long.valueOf(ctx.INT().getText());
		}
		String qos = null;
		if (ctx.QOS() != null) {
			if (ctx.STRING() != null) {
				qos = SqlParseSupport.unquote(ctx.STRING().getText());
			} else if (ctx.ID() != null) {
				qos = ctx.ID().getText();
			}
		}
		return new PinSql(table, keyLit, ttl, qos);
	}

	private static UnpinSql parseUnpin(SimplifiedSqlParser.UnpinStmtContext ctx) {
		return new UnpinSql(ctx.tableName().getText(), SqlParseSupport.literal(ctx.value()));
	}

	private static PrepareSql parsePrepare(SimplifiedSqlParser.PrepareStmtContext ctx, CommonTokenStream tokens) {
		final SimplifiedSqlParser.ExecutableContext body = ctx.executable();
		if (body.beginStmt() != null
				|| body.commitStmt() != null
				|| body.rollbackStmt() != null
				|| body.savepointStmt() != null
				|| body.rollbackToSavepointStmt() != null
				|| body.releaseSavepointStmt() != null
				|| body.prepareStmt() != null
				|| body.executeStmt() != null
				|| body.deallocateStmt() != null) {
			throw new IllegalArgumentException("cannot PREPARE control statement");
		}
		final String name = ctx.ID().getText();
		// Reconstruct body from original char stream (keeps whitespace; WS is on hidden channel).
		final int start = body.start.getStartIndex();
		final int stop = body.stop.getStopIndex();
		final String bodySql = tokens.getTokenSource().getInputStream().getText(Interval.of(start, stop));
		return new PrepareSql(name, bodySql);
	}

	private static ExecuteSql parseExecute(SimplifiedSqlParser.ExecuteStmtContext ctx) {
		final String name = ctx.ID().getText();
		final List<ValueContext> values = ctx.value();
		if (values == null || values.isEmpty()) {
			return new ExecuteSql(name, null);
		}
		final Object[] binds = new Object[values.size()];
		for (int i = 0; i < values.size(); i++) {
			binds[i] = SqlParseSupport.literal(values.get(i));
		}
		return new ExecuteSql(name, binds);
	}

	private static Stmt parseQuery(QueryContext query, String sql) {
		if (query.selectExprQuery() != null) {
			return parseSelectExpr(query.selectExprQuery(), sql);
		}
		final SimplifiedSqlParser.SelectQueryContext head = query.selectQuery();
		final List<SimplifiedSqlParser.UnionTailContext> tails = query.unionTail();
		if (tails == null || tails.isEmpty()) {
			return parseSelect(head, sql);
		}
		final List<SelectSql> arms = new ArrayList<>(1 + tails.size());
		final List<SetOpKind> opsBetween = new ArrayList<>(tails.size());
		final List<Boolean> allBetween = new ArrayList<>(tails.size());
		arms.add(parseSelect(head, SqlParseSupport.textOf(head)));
		for (SimplifiedSqlParser.UnionTailContext tail : tails) {
			opsBetween.add(parseSetOpKind(tail.setOperator()));
			allBetween.add(tail.ALL() != null);
			final SimplifiedSqlParser.SelectQueryContext arm = tail.selectQuery();
			arms.add(parseSelect(arm, SqlParseSupport.textOf(arm)));
		}
		return new SetOpSql(sql, List.copyOf(arms), List.copyOf(opsBetween), List.copyOf(allBetween));
	}

	/**
	 * Parse bare {@code SELECT item [, item]*} (no FROM) into an expression-only {@link SelectSql}.
	 */
	private static SelectSql parseSelectExpr(
			SimplifiedSqlParser.SelectExprQueryContext expr,
			String sql
	) {
		final List<String> projection = new ArrayList<>();
		final List<SelectItem> selectItems = new ArrayList<>();
		if (expr.selectItem() == null || expr.selectItem().isEmpty()) {
			throw new IllegalArgumentException("expression SELECT requires at least one select item");
		}
		for (SimplifiedSqlParser.SelectItemContext itemCtx : expr.selectItem()) {
			if (itemCtx.functionCall() != null) {
				final FunctionSelectItem fnItem = parseFunctionSelectItem(itemCtx);
				selectItems.add(fnItem);
				projection.add(fnItem.label());
			} else if (itemCtx.columnName() != null) {
				throw new IllegalArgumentException(
						"expression SELECT without FROM cannot reference columns: " + itemCtx.getText());
			} else if (itemCtx.aggregateExpr() != null || itemCtx.windowExpr() != null) {
				throw new IllegalArgumentException(
						"expression SELECT without FROM does not support aggregates/windows");
			} else {
				throw new IllegalArgumentException("unsupported select item in expression SELECT: " + itemCtx.getText());
			}
		}
		return new SelectSql(
				sql,
				null,
				List.copyOf(projection),
				List.copyOf(selectItems),
				null,
				null,
				List.of(),
				false,
				false,
				null,
				false,
				List.of(),
				0,
				null,
				false,
				null,
				false,
				false,
				null,
				List.of(),
				null,
				List.of(),
				null,
				false,
				false);
	}

	private static SetOpKind parseSetOpKind(SetOperatorContext op) {
		if (op == null) {
			throw new IllegalArgumentException("set operator is required");
		}
		if (op.INTERSECT() != null) {
			return SetOpKind.INTERSECT;
		}
		if (op.EXCEPT() != null) {
			return SetOpKind.EXCEPT;
		}
		if (op.UNION() != null) {
			return SetOpKind.UNION;
		}
		throw new IllegalArgumentException("unsupported set operator");
	}

	private static SelectSql parseSelect(SimplifiedSqlParser.SelectQueryContext query, String sql) {
		final SimplifiedSqlParser.FromItemContext from = query.fromItem();
		final FunctionFrom fromFunction;
		final String table;
		if (from.functionCall() != null) {
			final SimplifiedSqlParser.FunctionCallContext fc = from.functionCall();
			fromFunction = new FunctionFrom(
					fc.ID().getText(),
					parseFuncArgs(fc),
					from.alias != null ? from.alias.getText() : null);
			table = fromFunction.aliasOrNull() != null ? fromFunction.aliasOrNull() : fromFunction.functionName();
		} else {
			fromFunction = null;
			table = from.tableName().getText();
		}
		final List<String> projection = new ArrayList<>();
		final List<SelectItem> selectItems = new ArrayList<>();
		boolean aggregate = false;
		boolean countStar = false;
		String sumCol = null;
		boolean avg = false;
		boolean minAgg = false;
		boolean maxAgg = false;
		String windowFunc = null;
		List<String> windowPartition = List.of();
		String windowOrderCol = null;
		final Map<String, SimplifiedSqlParser.WindowSpecContext> namedWindows = new LinkedHashMap<>();
		if (query.windowClause() != null) {
			for (SimplifiedSqlParser.WindowDefContext def : query.windowClause().windowDef()) {
				namedWindows.put(def.ID().getText().toLowerCase(Locale.ROOT), def.windowSpec());
			}
		}
		final SelectListContext sl = query.selectList();
		if (sl != null && "*".equals(sl.getText())) {
			projection.add("*");
		} else if (sl != null && sl.selectItem() != null && !sl.selectItem().isEmpty()) {
			for (SimplifiedSqlParser.SelectItemContext itemCtx : sl.selectItem()) {
				if (itemCtx.aggregateExpr() != null) {
					aggregate = true;
					final AggregateSelectItem aggItem = parseAggregateItem(itemCtx.aggregateExpr());
					selectItems.add(aggItem);
					projection.add(aggItem.label());
					if (!countStar && sumCol == null && !minAgg && !maxAgg) {
						countStar = aggItem.countStar();
						sumCol = aggItem.sumColumnOrNull();
						avg = aggItem.avg();
						minAgg = aggItem.minAgg();
						maxAgg = aggItem.maxAgg();
					}
				} else if (itemCtx.windowExpr() != null) {
					final WindowSelectItem winItem = parseWindowItem(itemCtx.windowExpr(), namedWindows);
					selectItems.add(winItem);
					projection.add(winItem.label());
					if (windowFunc == null) {
						windowFunc = winItem.func();
						sumCol = sumCol == null ? winItem.valueColumnOrNull() : sumCol;
						windowPartition = winItem.partitionColumns();
						windowOrderCol = winItem.orderColOrNull();
					}
				} else if (itemCtx.functionCall() != null) {
					final FunctionSelectItem fnItem = parseFunctionSelectItem(itemCtx);
					selectItems.add(fnItem);
					projection.add(fnItem.label());
				} else if (itemCtx.columnName() != null) {
					final ColumnNameContext cn = itemCtx.columnName();
					final String col = simpleColumn(cn);
					final String tableQual = cn.ID().size() > 1 ? cn.ID(0).getText() : null;
					final String alias = itemCtx.alias != null ? itemCtx.alias.getText() : null;
					selectItems.add(new ColumnSelectItem(col, tableQual, alias));
					projection.add(alias != null ? alias : (tableQual != null ? tableQual + "." + col : col));
				}
			}
		} else {
			projection.add("*");
		}
		final List<JoinEdge> joins = new ArrayList<>();
		if (query.joinClause() != null) {
			for (SimplifiedSqlParser.JoinClauseContext jc : query.joinClause()) {
				joins.add(new JoinEdge(
						jc.tableName().getText(),
						simpleColumn(jc.columnName(0)),
						simpleColumn(jc.columnName(1)),
						joinKindOf(jc)
				));
			}
		}
		List<String> groupByColumns = List.of();
		if (query.GROUP() != null && query.groupByList() != null) {
			final List<String> cols = new ArrayList<>();
			for (ColumnNameContext c : query.groupByList().columnName()) {
				cols.add(simpleColumn(c));
			}
			groupByColumns = List.copyOf(cols);
			aggregate = true;
		}
		HavingPredicate having = null;
		if (query.HAVING() != null && query.havingExpr != null) {
			having = parseHaving(query.havingExpr);
		}
		final List<WhereSubquery> whereSubs = new ArrayList<>();
		if (query.WHERE() != null && !query.expression().isEmpty()) {
			collectWhereSubqueries(query.expression(0), whereSubs);
		}
		String pkCol = null;
		Object pkVal = null;
		if (joins.isEmpty()
				&& whereSubs.isEmpty()
				&& query.WHERE() != null
				&& !query.expression().isEmpty()
				&& query.expression(0) instanceof SimplifiedSqlParser.PredicateExpressionContext pred
				&& pred.predicate() instanceof SimplifiedSqlParser.ComparisonContext cmp
				&& "=".equals(cmp.operator().getText())) {
			pkCol = simpleColumn(cmp.columnName());
			pkVal = SqlParseSupport.literal(cmp.value());
		}
		int offset = 0;
		Integer limitOrNull = null;
		if (query.LIMIT() != null && query.limitClause() != null) {
			final SimplifiedSqlParser.LimitClauseContext lim = query.limitClause();
			if (lim.INT(1) != null) {
				offset = Math.max(0, Integer.parseInt(lim.INT(0).getText()));
				limitOrNull = Math.max(0, Integer.parseInt(lim.INT(1).getText()));
			} else if (lim.INT(0) != null) {
				limitOrNull = Math.max(0, Integer.parseInt(lim.INT(0).getText()));
			}
		}
		if (query.OFFSET() != null && query.offsetInt != null) {
			if (query.LIMIT() != null && query.limitClause() != null && query.limitClause().INT(1) != null) {
				throw new IllegalArgumentException("cannot combine LIMIT offset,count with OFFSET clause");
			}
			offset = Math.max(0, Integer.parseInt(query.offsetInt.getText()));
		}
		final boolean distinct = query.DISTINCT() != null;
		return new SelectSql(
				sql, table, projection, List.copyOf(selectItems), pkCol, pkVal, List.copyOf(joins),
				aggregate, countStar, sumCol, avg, groupByColumns, offset, limitOrNull,
				distinct, having, minAgg, maxAgg,
				windowFunc, windowPartition, windowOrderCol,
				List.copyOf(whereSubs), fromFunction, query.forUpdateClause() != null,
				query.forUpdateClause() != null && query.forUpdateClause().LOCKED() != null);
	}

	private static void collectWhereSubqueries(
			SimplifiedSqlParser.ExpressionContext expr,
			List<WhereSubquery> out
	) {
		if (expr == null) {
			return;
		}
		if (expr instanceof SimplifiedSqlParser.AndExpressionContext and) {
			collectWhereSubqueries(and.expression(0), out);
			collectWhereSubqueries(and.expression(1), out);
			return;
		}
		if (expr instanceof SimplifiedSqlParser.OrExpressionContext or) {
			collectWhereSubqueries(or.expression(0), out);
			collectWhereSubqueries(or.expression(1), out);
			return;
		}
		if (expr instanceof SimplifiedSqlParser.NotExpressionContext not) {
			collectWhereSubqueries(not.expression(), out);
			return;
		}
		if (expr instanceof SimplifiedSqlParser.ParenExpressionContext paren) {
			collectWhereSubqueries(paren.expression(), out);
			return;
		}
		if (expr instanceof SimplifiedSqlParser.PredicateExpressionContext pred) {
			if (pred.predicate() instanceof SimplifiedSqlParser.InSubqueryContext inSub) {
				final SimplifiedSqlParser.SelectQueryContext sq = inSub.selectQuery();
				final String subSql = SqlParseSupport.textOf(sq);
				out.add(new WhereSubquery(
						simpleColumn(inSub.columnName()),
						true,
						false,
						"IN",
						parseSelect(sq, subSql),
						subSql));
				return;
			}
			if (pred.predicate() instanceof SimplifiedSqlParser.ExistsSubqueryContext existsSub) {
				final SimplifiedSqlParser.SelectQueryContext sq = existsSub.selectQuery();
				final String subSql = SqlParseSupport.textOf(sq);
				out.add(new WhereSubquery(
						null,
						false,
						true,
						"EXISTS",
						parseSelect(sq, subSql),
						subSql));
				return;
			}
			if (pred.predicate() instanceof SimplifiedSqlParser.ComparisonSubqueryContext cmpSub) {
				final String op = cmpSub.operator().getText();
				if (!"=".equals(op)) {
					throw new IllegalArgumentException(
							"scalar subquery supports only = comparison, got: " + op);
				}
				final SimplifiedSqlParser.SelectQueryContext sq = cmpSub.selectQuery();
				final String subSql = SqlParseSupport.textOf(sq);
				out.add(new WhereSubquery(
						simpleColumn(cmpSub.columnName()),
						false,
						false,
						op,
						parseSelect(sq, subSql),
						subSql));
			}
		}
	}

	private static HavingPredicate parseHaving(SimplifiedSqlParser.ExpressionContext expr) {
		if (expr == null) {
			return null;
		}
		if (expr instanceof SimplifiedSqlParser.ParenExpressionContext paren) {
			return parseHaving(paren.expression());
		}
		if (expr instanceof SimplifiedSqlParser.PredicateExpressionContext pred) {
			if (pred.predicate() instanceof SimplifiedSqlParser.AggComparisonContext agg) {
				final AggregateSelectItem item = parseAggregateItem(agg.aggregateExpr());
				return new HavingPredicate(item.label(), agg.operator().getText(), SqlParseSupport.literal(agg.value()));
			}
			if (pred.predicate() instanceof SimplifiedSqlParser.ComparisonContext cmp) {
				return new HavingPredicate(
						simpleColumn(cmp.columnName()),
						cmp.operator().getText(),
						SqlParseSupport.literal(cmp.value()));
			}
		}
		throw new IllegalArgumentException("unsupported HAVING expression: " + SqlParseSupport.textOf(expr));
	}

	private static AggregateSelectItem parseAggregateItem(SimplifiedSqlParser.AggregateExprContext agg) {
		if (agg.COUNT() != null) {
			return new AggregateSelectItem("COUNT(*)", true, null, false, false, false);
		}
		if (agg.SUM() != null) {
			final String col = simpleColumn(agg.columnName());
			return new AggregateSelectItem("SUM(" + col + ")", false, col, false, false, false);
		}
		if (agg.AVG() != null) {
			final String col = simpleColumn(agg.columnName());
			return new AggregateSelectItem("AVG(" + col + ")", false, col, true, false, false);
		}
		if (agg.MIN() != null) {
			final String col = simpleColumn(agg.columnName());
			return new AggregateSelectItem("MIN(" + col + ")", false, col, false, true, false);
		}
		if (agg.MAX() != null) {
			final String col = simpleColumn(agg.columnName());
			return new AggregateSelectItem("MAX(" + col + ")", false, col, false, false, true);
		}
		throw new IllegalArgumentException("unsupported aggregate expression");
	}

	private static WindowSelectItem parseWindowItem(
			SimplifiedSqlParser.WindowExprContext we,
			Map<String, SimplifiedSqlParser.WindowSpecContext> namedWindows
	) {
		String func;
		String valueCol = null;
		String label;
		if (we.ROW_NUMBER() != null) {
			func = "ROW_NUMBER";
			label = "ROW_NUMBER()";
		} else if (we.RANK() != null) {
			func = "RANK";
			label = "RANK()";
		} else if (we.DENSE_RANK() != null) {
			func = "DENSE_RANK";
			label = "DENSE_RANK()";
		} else if (we.LAG() != null) {
			func = "LAG";
			valueCol = simpleColumn(we.columnName());
			label = "LAG(" + valueCol + ")";
		} else if (we.LEAD() != null) {
			func = "LEAD";
			valueCol = simpleColumn(we.columnName());
			label = "LEAD(" + valueCol + ")";
		} else if (we.SUM() != null) {
			func = "SUM";
			valueCol = simpleColumn(we.columnName());
			label = "SUM(" + valueCol + ")";
		} else if (we.MIN() != null) {
			func = "MIN";
			valueCol = simpleColumn(we.columnName());
			label = "MIN(" + valueCol + ")";
		} else if (we.MAX() != null) {
			func = "MAX";
			valueCol = simpleColumn(we.columnName());
			label = "MAX(" + valueCol + ")";
		} else if (we.AVG() != null) {
			func = "AVG";
			valueCol = simpleColumn(we.columnName());
			label = "AVG(" + valueCol + ")";
		} else {
			throw new IllegalArgumentException("unsupported window expression");
		}
		List<String> partition = List.of();
		String orderCol = null;
		final SimplifiedSqlParser.OverClauseContext over = we.overClause();
		if (over != null) {
			SimplifiedSqlParser.WindowSpecContext spec = over.windowSpec();
			if (spec == null && over.windowName != null) {
				spec = namedWindows.get(over.windowName.getText().toLowerCase(Locale.ROOT));
				if (spec == null) {
					throw new IllegalArgumentException("Unknown WINDOW name: " + over.windowName.getText());
				}
			}
			if (spec != null && spec.partitionByList() != null) {
				final List<String> parts = new ArrayList<>();
				for (ColumnNameContext c : spec.partitionByList().columnName()) {
					parts.add(simpleColumn(c));
				}
				partition = List.copyOf(parts);
			}
			if (spec != null && spec.orderList() != null && !spec.orderList().orderItem().isEmpty()) {
				orderCol = simpleColumn(spec.orderList().orderItem(0).columnName());
			}
		}
		return new WindowSelectItem(label, func, valueCol, partition, orderCol);
	}

	private static CreateViewSql parseCreateView(SimplifiedSqlParser.CreateViewStmtContext ctx) {
		final String table = ctx.tableName().getText();
		final String selectSql = SqlParseSupport.textOf(ctx.query());
		return new CreateViewSql(table, selectSql);
	}

	private static DropViewSql parseDropView(SimplifiedSqlParser.DropViewStmtContext ctx) {
		return new DropViewSql(ctx.tableName().getText(), ctx.IF() != null);
	}

	private static CreateMaterializedViewSql parseCreateMaterializedView(
			SimplifiedSqlParser.CreateMaterializedViewStmtContext ctx
	) {
		return new CreateMaterializedViewSql(ctx.tableName().getText(), SqlParseSupport.textOf(ctx.query()));
	}

	private static CreateFunctionSql parseCreateFunction(SimplifiedSqlParser.CreateFunctionStmtContext ctx) {
		final String name = ctx.ID().getText();
		final List<String> paramNames = new ArrayList<>();
		final List<String> paramTypes = new ArrayList<>();
		if (ctx.funcParam() != null) {
			for (SimplifiedSqlParser.FuncParamContext p : ctx.funcParam()) {
				paramNames.add(p.ID().getText());
				paramTypes.add(p.typeName().getText());
			}
		}
		final boolean returnsTable = ctx.TABLE() != null;
		final List<String> tableCols = new ArrayList<>();
		final List<String> tableTypes = new ArrayList<>();
		if (returnsTable && ctx.tableFuncCol() != null) {
			for (SimplifiedSqlParser.TableFuncColContext col : ctx.tableFuncCol()) {
				tableCols.add(col.ID().getText());
				tableTypes.add(col.typeName().getText());
			}
		}
		final String returnType = returnsTable ? null : ctx.typeName().getText();
		return new CreateFunctionSql(
				name,
				List.copyOf(paramNames),
				List.copyOf(paramTypes),
				returnType,
				returnsTable,
				List.copyOf(tableCols),
				List.copyOf(tableTypes),
				SqlParseSupport.unquote(ctx.STRING(0).getText()),
				SqlParseSupport.unquote(ctx.STRING(1).getText())
		);
	}

	private static CreateTriggerSql parseCreateTrigger(SimplifiedSqlParser.CreateTriggerStmtContext ctx) {
		final String name = ctx.ID().getText();
		final String timing = ctx.BEFORE() != null ? "BEFORE" : "AFTER";
		final String event;
		if (ctx.INSERT() != null) {
			event = "INSERT";
		} else if (ctx.UPDATE() != null) {
			event = "UPDATE";
		} else {
			event = "DELETE";
		}
		final String granularity = ctx.STATEMENT() != null ? "STATEMENT" : "ROW";
		final String table = ctx.tableName().getText();
		final String whenSql;
		final String bodySql;
		if (ctx.WHEN() != null) {
			whenSql = SqlParseSupport.unquote(ctx.STRING(0).getText());
			bodySql = SqlParseSupport.unquote(ctx.STRING(1).getText());
		} else {
			whenSql = null;
			bodySql = SqlParseSupport.unquote(ctx.STRING(0).getText());
		}
		return new CreateTriggerSql(name, timing, event, granularity, table, whenSql, bodySql);
	}

	private static DropTriggerSql parseDropTrigger(SimplifiedSqlParser.DropTriggerStmtContext ctx) {
		final String table = ctx.tableName() == null ? null : ctx.tableName().getText();
		return new DropTriggerSql(ctx.ID().getText(), ctx.IF() != null, table);
	}

	private static FunctionSelectItem parseFunctionSelectItem(SimplifiedSqlParser.SelectItemContext itemCtx) {
		final SimplifiedSqlParser.FunctionCallContext fc = itemCtx.functionCall();
		final String name = fc.ID().getText();
		final List<FuncArg> args = parseFuncArgs(fc);
		final String alias = itemCtx.alias != null ? itemCtx.alias.getText() : null;
		final String label = alias != null ? alias : functionCallLabel(name, args);
		return new FunctionSelectItem(label, name, args);
	}

	private static List<FuncArg> parseFuncArgs(SimplifiedSqlParser.FunctionCallContext fc) {
		final List<FuncArg> args = new ArrayList<>();
		if (fc.funcArg() == null) {
			return List.of();
		}
		for (SimplifiedSqlParser.FuncArgContext arg : fc.funcArg()) {
			if (arg.columnName() != null) {
				args.add(new ColumnFuncArg(simpleColumn(arg.columnName())));
			} else {
				args.add(new LiteralFuncArg(SqlParseSupport.literal(arg.value())));
			}
		}
		return List.copyOf(args);
	}

	private static String functionCallLabel(String name, List<FuncArg> args) {
		final StringBuilder sb = new StringBuilder(name).append('(');
		for (int i = 0; i < args.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			final FuncArg a = args.get(i);
			if (a instanceof ColumnFuncArg c) {
				sb.append(c.column());
			} else if (a instanceof LiteralFuncArg lit) {
				sb.append(lit.value());
			}
		}
		return sb.append(')').toString();
	}

	private static RecursiveCteSql parseRecursiveCte(
			SimplifiedSqlParser.WithQueryContext wq,
			String fullSql
	) {
		if (wq.cteDef() == null || wq.cteDef().size() != 1) {
			throw new IllegalArgumentException("WITH RECURSIVE supports exactly one CTE definition");
		}
		final SimplifiedSqlParser.CteDefContext cte = wq.cteDef(0);
		final String cteName = cte.ID().getText();
		final QueryContext body = cte.query();
		final List<SimplifiedSqlParser.UnionTailContext> tails = body.unionTail();
		if (tails == null || tails.size() != 1) {
			throw new IllegalArgumentException(
					"WITH RECURSIVE body must be anchor UNION [ALL] recursive (exactly one UNION)");
		}
		final SimplifiedSqlParser.UnionTailContext tail = tails.getFirst();
		if (parseSetOpKind(tail.setOperator()) != SetOpKind.UNION) {
			throw new IllegalArgumentException(
					"WITH RECURSIVE body must be anchor UNION [ALL] recursive (INTERSECT/EXCEPT not allowed)");
		}
		final SelectSql anchor = parseSelect(body.selectQuery(), SqlParseSupport.textOf(body.selectQuery()));
		final SelectSql recursive = parseSelect(tail.selectQuery(), SqlParseSupport.textOf(tail.selectQuery()));
		final boolean unionAll = tail.ALL() != null;
		final SelectSql outer = parseSelect(wq.query().selectQuery(), SqlParseSupport.textOf(wq.query()));
		if (wq.query().unionTail() != null && !wq.query().unionTail().isEmpty()) {
			throw new IllegalArgumentException("outer SELECT after WITH RECURSIVE cannot be a set op");
		}
		return new RecursiveCteSql(cteName, anchor, recursive, unionAll, outer, fullSql);
	}

	private static InsertSql parseInsert(InsertStmtContext ctx) {
		final String table = ctx.tableName().getText();
		final List<String> cols = new ArrayList<>();
		if (ctx.insertColumnList() != null) {
			for (ColumnNameContext c : ctx.insertColumnList().columnName()) {
				cols.add(c.getText());
			}
		}
		final List<List<Object>> rows = new ArrayList<>();
		for (ValueTupleContext tuple : ctx.valueTuple()) {
			final List<Object> vals = new ArrayList<>();
			for (ValueContext v : tuple.value()) {
				vals.add(SqlParseSupport.literal(v));
			}
			rows.add(vals);
		}
		final boolean upsertKeyword = ctx.UPSERT() != null;
		if (upsertKeyword && ctx.onConflictClause() != null) {
			throw new IllegalArgumentException("UPSERT cannot combine with ON CONFLICT");
		}
		final OnConflict conflict = upsertKeyword
				? new OnConflict(List.of(), ConflictAction.DO_UPSERT_VALUES, Map.of())
				: parseOnConflict(ctx.onConflictClause());
		return new InsertSql(table, cols, rows, conflict, parseReturning(ctx.returningClause()));
	}

	private static OnConflict parseOnConflict(SimplifiedSqlParser.OnConflictClauseContext ctx) {
		if (ctx == null) {
			return null;
		}
		final List<String> targets = new ArrayList<>();
		if (ctx.columnName() != null) {
			for (ColumnNameContext c : ctx.columnName()) {
				targets.add(simpleColumn(c));
			}
		}
		final SimplifiedSqlParser.ConflictActionContext actionCtx = ctx.conflictAction();
		if (actionCtx.NOTHING() != null) {
			return new OnConflict(List.copyOf(targets), ConflictAction.DO_NOTHING, Map.of());
		}
		final Map<String, Object> sets = new LinkedHashMap<>();
		for (UpdateAssignContext a : actionCtx.updateAssign()) {
			final String col = a.columnName() != null ? simpleColumn(a.columnName()) : null;
			if (a.updateRhs() != null) {
				throw new IllegalArgumentException("ON CONFLICT DO UPDATE supports literal SET only in v1");
			}
			sets.put(col, SqlParseSupport.literal(a.value()));
		}
		return new OnConflict(List.copyOf(targets), ConflictAction.DO_UPDATE, Map.copyOf(sets));
	}

	private static MergeSql parseMerge(SimplifiedSqlParser.MergeStmtContext ctx) {
		final String target = ctx.tableName().getText();
		final SimplifiedSqlParser.MergeSourceContext src = ctx.mergeSource();
		String sourceTable = null;
		List<Object> sourceRow = null;
		if (src.tableName() != null) {
			sourceTable = src.tableName().getText();
		} else {
			sourceRow = new ArrayList<>();
			for (ValueContext v : src.valueTuple().value()) {
				sourceRow.add(SqlParseSupport.literal(v));
			}
		}
		final List<ColumnNameContext> onCols = ctx.columnName();
		final String targetOn = simpleColumn(onCols.get(0));
		final String sourceOn = simpleColumn(onCols.get(1));
		Map<String, Object> matchedSets = null;
		if (ctx.whenMatchedClause() != null) {
			matchedSets = new LinkedHashMap<>();
			for (UpdateAssignContext a : ctx.whenMatchedClause().updateAssign()) {
				final String col = a.columnName() != null ? simpleColumn(a.columnName()) : null;
				if (a.updateRhs() != null) {
					throw new IllegalArgumentException("MERGE WHEN MATCHED supports literal SET only in v1");
				}
				matchedSets.put(col, SqlParseSupport.literal(a.value()));
			}
		}
		List<String> insertCols = null;
		List<Object> insertVals = null;
		if (ctx.whenNotMatchedClause() != null) {
			final SimplifiedSqlParser.WhenNotMatchedClauseContext nm = ctx.whenNotMatchedClause();
			insertCols = new ArrayList<>();
			if (nm.insertColumnList() != null) {
				for (ColumnNameContext c : nm.insertColumnList().columnName()) {
					insertCols.add(c.getText());
				}
			}
			insertVals = new ArrayList<>();
			for (ValueContext v : nm.valueTuple().value()) {
				insertVals.add(SqlParseSupport.literal(v));
			}
		}
		return new MergeSql(
				target,
				sourceTable,
				sourceRow == null ? null : List.copyOf(sourceRow),
				targetOn,
				sourceOn,
				matchedSets == null ? null : Map.copyOf(matchedSets),
				insertCols == null ? null : List.copyOf(insertCols),
				insertVals == null ? null : List.copyOf(insertVals)
		);
	}

	private static DeleteSql parseDelete(DeleteStmtContext ctx) {
		final String table = ctx.tableName().getText();
		final String whereSql = SqlParseSupport.textOf(ctx.expression());
		final PkEq pk = extractPkEq(ctx.expression());
		return new DeleteSql(table, whereSql, pk == null ? null : pk.column(), pk == null ? null : pk.value());
	}

	private static UpdateSql parseUpdate(UpdateStmtContext ctx) {
		final String table = ctx.targetTable.getText();
		final String whereSql = SqlParseSupport.textOf(ctx.expression());
		final PkEq pk = extractPkEq(ctx.expression());
		final String pkCol = pk == null ? null : pk.column();
		final Object pkVal = pk == null ? null : pk.value();
		final List<UpdatePlan.FieldAssign> rmw = new ArrayList<>();
		final Map<String, Object> lits = new LinkedHashMap<>();
		for (UpdateAssignContext a : ctx.updateAssign()) {
			final String col = a.columnName() != null ? simpleColumn(a.columnName()) : null;
			if (a.updateRhs() != null) {
				rmw.add(parseRmw(col, a.updateRhs()));
			} else {
				lits.put(col, SqlParseSupport.literal(a.value()));
			}
		}
		final String sourceTable = ctx.sourceTable == null ? null : ctx.sourceTable.getText();
		String targetJoinColumn = null;
		String sourceJoinColumn = null;
		if (sourceTable != null) {
			if (!rmw.isEmpty()) {
				throw new IllegalArgumentException("UPDATE FROM supports literal SET only");
			}
			if (!(ctx.expression() instanceof SimplifiedSqlParser.PredicateExpressionContext predicateExpression)
					|| !(predicateExpression.predicate() instanceof SimplifiedSqlParser.ColumnComparisonContext comparison)) {
				throw new IllegalArgumentException("UPDATE FROM requires one column equality");
			}
			final List<ColumnNameContext> joinColumns = comparison.columnName();
			if (joinColumns.size() != 2 || !"=".equals(comparison.operator().getText())) {
				throw new IllegalArgumentException("UPDATE FROM requires one column equality");
			}
			targetJoinColumn = simpleColumn(joinColumns.get(0));
			sourceJoinColumn = simpleColumn(joinColumns.get(1));
		}
		if (!rmw.isEmpty()) {
			if (pkCol == null) {
				throw new IllegalArgumentException("RMW UPDATE requires PK equality WHERE in v1.1");
			}
			final String templateKey = "antlr:" + table + ":" + rmw.size() + ":lit" + lits.size();
			return new UpdateSql(table, new UpdatePlan(table, rmw, pkCol, pkVal, templateKey),
					Map.copyOf(lits),
					whereSql, pkCol, pkVal, null, null, null, parseReturning(ctx.returningClause()));
		}
		return new UpdateSql(table, null, lits, whereSql, pkCol, pkVal,
				sourceTable, targetJoinColumn, sourceJoinColumn,
				parseReturning(ctx.returningClause()));
	}

	private static List<String> parseReturning(SimplifiedSqlParser.ReturningClauseContext ctx) {
		if (ctx == null) {
			return List.of();
		}
		if (ctx.columnList().columnName() == null || ctx.columnList().columnName().isEmpty()) {
			return List.of("*");
		}
		final List<String> columns = new ArrayList<>();
		for (ColumnNameContext column : ctx.columnList().columnName()) {
			columns.add(simpleColumn(column));
		}
		return List.copyOf(columns);
	}

	private record PkEq(String column, Object value) {
	}

	/** Unqualified column id ({@code t.col} тЖТ {@code col}). */
	private static String simpleColumn(ColumnNameContext ctx) {
		if (ctx == null || ctx.ID() == null || ctx.ID().isEmpty()) {
			throw new IllegalArgumentException("missing column name");
		}
		return ctx.ID(ctx.ID().size() - 1).getText();
	}

	private static PkEq extractPkEq(org.genfork.grid.antlr.SimplifiedSqlParser.ExpressionContext expr) {
		if (expr instanceof SimplifiedSqlParser.PredicateExpressionContext pred
				&& pred.predicate() instanceof SimplifiedSqlParser.ComparisonContext cmp
				&& "=".equals(cmp.operator().getText())) {
			return new PkEq(simpleColumn(cmp.columnName()), SqlParseSupport.literal(cmp.value()));
		}
		return null;
	}
	private static UpdatePlan.FieldAssign parseRmw(String col, UpdateRhsContext rhs) {
		if (rhs.CONCAT() != null) {
			return new UpdatePlan.FieldAssign(col, ModifyPayload.KIND_STRING_CONCAT, SqlParseSupport.stringLit(rhs.value(0)));
		}
		if (rhs.CONCAT_OP() != null && !rhs.CONCAT_OP().isEmpty()) {
			final StringBuilder arg = new StringBuilder();
			for (ValueContext v : rhs.value()) {
				arg.append(SqlParseSupport.stringLit(v));
			}
			return new UpdatePlan.FieldAssign(col, ModifyPayload.KIND_STRING_CONCAT, arg.toString());
		}
		if (rhs.value() != null && !rhs.value().isEmpty()) {
			return new UpdatePlan.FieldAssign(col, ModifyPayload.KIND_NUMERIC_ADD, rhs.value(0).getText());
		}
		throw new IllegalArgumentException("Bad UPDATE RHS for " + col);
	}
	private static CreateTableSql parseCreateTable(CreateTableStmtContext ctx) {
		final String table = ctx.tableName().getText();
		final boolean ifNotExists = ctx.IF() != null;
		final List<ColumnSpec> cols = new ArrayList<>();
		final List<FkSpec> fks = new ArrayList<>();
		final List<CheckSpec> checks = new ArrayList<>();
		List<String> tablePk = List.of();
		for (TableElementContext el : ctx.tableElement()) {
			if (el.FOREIGN() != null) {
				fks.add(parseFkSpec(el));
				continue;
			}
			if (el.CHECK() != null) {
				checks.add(new CheckSpec(
						el.constraintName() == null ? null : el.constraintName().getText(),
						SqlParseSupport.textOf(el.expression())));
				continue;
			}
			if (el.columnDef() == null) {
				if (el.PRIMARY() != null) {
					tablePk = el.columnName().stream().map(ColumnNameContext::getText).toList();
				}
				continue;
			}
			final ColumnDefContext cd = el.columnDef();
			if (cd.serialType() != null) {
				final String serialTok = cd.serialType().getText().toUpperCase(Locale.ROOT);
				final String typeTok = "BIGSERIAL".equals(serialTok) ? "BIGINT" : "INT";
				cols.add(new ColumnSpec(
						cd.columnName().getText(),
						typeTok,
						true,
						cd.PRIMARY() != null,
						true,
						true
				));
				continue;
			}
			cols.add(new ColumnSpec(
					cd.columnName().getText(),
					cd.typeName().getText(),
					cd.NOT() != null,
					cd.PRIMARY() != null,
					cd.identityClause() != null,
					false
			));
		}
		return new CreateTableSql(table, ifNotExists, cols, tablePk, fks, checks);
	}

	private static FkSpec parseFkSpec(TableElementContext el) {
		final String name = el.constraintName() == null ? null : el.constraintName().getText();
		final List<ColumnNameContext> names = el.columnName();
		final int totalCols = names.size();
		final int half = totalCols / 2;
		final List<String> childCols = new ArrayList<>(half);
		final List<String> parentCols = new ArrayList<>(half);
		for (int i = 0; i < half; i++) {
			childCols.add(names.get(i).getText());
		}
		for (int i = half; i < totalCols; i++) {
			parentCols.add(names.get(i).getText());
		}
		String onDelete = null;
		String onUpdate = null;
		final List<SimplifiedSqlParser.ReferentialActionContext> actions = el.referentialAction();
		if (el.DELETE() != null && !actions.isEmpty()) {
			onDelete = actions.getFirst().getText();
			if (el.UPDATE() != null && actions.size() > 1) {
				onUpdate = actions.get(1).getText();
			}
		} else if (el.UPDATE() != null && !actions.isEmpty()) {
			onUpdate = actions.getFirst().getText();
		}
		return new FkSpec(
				name,
				childCols,
				el.tableName().getText(),
				parentCols,
				onDelete,
				onUpdate
		);
	}

	private static CreateSequenceSql parseCreateSequence(SimplifiedSqlParser.CreateSequenceStmtContext ctx) {
		long start = org.genfork.grid.catalog.SequenceDef.DEFAULT_START;
		long increment = org.genfork.grid.catalog.SequenceDef.DEFAULT_INCREMENT;
		final List<org.antlr.v4.runtime.tree.TerminalNode> ints = ctx.INT();
		int intIdx = 0;
		if (ctx.START() != null && intIdx < ints.size()) {
			start = Long.parseLong(ints.get(intIdx++).getText());
		}
		if (ctx.INCREMENT() != null && intIdx < ints.size()) {
			increment = Long.parseLong(ints.get(intIdx).getText());
		}
		return new CreateSequenceSql(
				ctx.sequenceName().getText(),
				ctx.IF() != null,
				start,
				increment,
				ctx.RECLAIM() != null
		);
	}

	private static DropSequenceSql parseDropSequence(SimplifiedSqlParser.DropSequenceStmtContext ctx) {
		return new DropSequenceSql(ctx.sequenceName().getText(), ctx.IF() != null);
	}

	private static SequenceValueSql parseSelectSequence(SimplifiedSqlParser.SelectSequenceStmtContext ctx) {
		return parseSequenceCallToSql(ctx.sequenceCall());
	}

	private static SequenceValueSql parseSequenceCallToSql(SimplifiedSqlParser.SequenceCallContext ctx) {
		final SequenceCallExpr expr = SqlParseSupport.sequenceCallExpr(ctx);
		return new SequenceValueSql(expr.nextVal(), expr.sequenceName());
	}

	private static DropTableSql parseDropTable(DropTableStmtContext ctx) {
		return new DropTableSql(ctx.tableName().getText(), ctx.IF() != null);
	}

	private static CreateIndexSql parseCreateIndex(CreateIndexStmtContext ctx) {
		IndexType kind = IndexType.LAX;
		if (ctx.BITMAP() != null) {
			kind = IndexType.BITMAP;
		} else if (ctx.UNIQUE() != null) {
			kind = IndexType.STRICT;
		}
		final List<String> cols = new ArrayList<>();
		for (ColumnNameContext c : ctx.columnName()) {
			cols.add(c.getText());
		}
		if (kind == IndexType.BITMAP && cols.size() > 1) {
			throw new IllegalArgumentException(IndexDef.BITMAP_SINGLE_COLUMN_ONLY);
		}
		return new CreateIndexSql(ctx.indexName().getText(), ctx.tableName().getText(), cols, kind);
	}

	private static DropIndexSql parseDropIndex(DropIndexStmtContext ctx) {
		final String table = ctx.tableName() == null ? null : ctx.tableName().getText();
		return new DropIndexSql(ctx.indexName().getText(), table);
	}

	private static AlterTableSql parseAlterTable(SimplifiedSqlParser.AlterTableStmtContext ctx) {
		final String table = ctx.tableName().getText();
		if (ctx.CHECK() != null) {
			return new AlterTableSql(
					table,
					null,
					null,
					new CheckSpec(
							ctx.constraintName() == null ? null : ctx.constraintName().getText(),
							SqlParseSupport.textOf(ctx.expression())));
		}
		if (ctx.ADD() != null) {
			final ColumnDefContext cd = ctx.columnDef();
			return new AlterTableSql(
					table,
					new ColumnSpec(
							cd.columnName().getText(),
							cd.typeName().getText(),
							cd.NOT() != null,
							cd.PRIMARY() != null
					),
					null
			);
		}
		return new AlterTableSql(table, null, ctx.columnName().getText());
	}

	private static JoinKind joinKindOf(SimplifiedSqlParser.JoinClauseContext jc) {
		if (jc.FULL() != null) {
			return JoinKind.FULL;
		}
		if (jc.RIGHT() != null) {
			return JoinKind.RIGHT;
		}
		if (jc.LEFT() != null) {
			return JoinKind.LEFT;
		}
		return JoinKind.INNER;
	}

}
