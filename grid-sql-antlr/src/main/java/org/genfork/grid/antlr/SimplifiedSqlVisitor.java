// Generated from SimplifiedSql.g4 by ANTLR 4.13.2
package org.genfork.grid.antlr;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link SimplifiedSqlParser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface SimplifiedSqlVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStatement(SimplifiedSqlParser.StatementContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#script}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitScript(SimplifiedSqlParser.ScriptContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#executable}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExecutable(SimplifiedSqlParser.ExecutableContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#createUserStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCreateUserStmt(SimplifiedSqlParser.CreateUserStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#dropUserStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDropUserStmt(SimplifiedSqlParser.DropUserStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#alterUserStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAlterUserStmt(SimplifiedSqlParser.AlterUserStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#createRoleStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCreateRoleStmt(SimplifiedSqlParser.CreateRoleStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#dropRoleStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDropRoleStmt(SimplifiedSqlParser.DropRoleStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#grantStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGrantStmt(SimplifiedSqlParser.GrantStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#revokeStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRevokeStmt(SimplifiedSqlParser.RevokeStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#privilegeList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrivilegeList(SimplifiedSqlParser.PrivilegeListContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#privilegeName}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrivilegeName(SimplifiedSqlParser.PrivilegeNameContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#privilegeTarget}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrivilegeTarget(SimplifiedSqlParser.PrivilegeTargetContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#pinStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPinStmt(SimplifiedSqlParser.PinStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#unpinStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUnpinStmt(SimplifiedSqlParser.UnpinStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#beginStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBeginStmt(SimplifiedSqlParser.BeginStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#commitStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCommitStmt(SimplifiedSqlParser.CommitStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#rollbackStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRollbackStmt(SimplifiedSqlParser.RollbackStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#savepointStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSavepointStmt(SimplifiedSqlParser.SavepointStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#rollbackToSavepointStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRollbackToSavepointStmt(SimplifiedSqlParser.RollbackToSavepointStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#releaseSavepointStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitReleaseSavepointStmt(SimplifiedSqlParser.ReleaseSavepointStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#prepareStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrepareStmt(SimplifiedSqlParser.PrepareStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#executeStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExecuteStmt(SimplifiedSqlParser.ExecuteStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#deallocateStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDeallocateStmt(SimplifiedSqlParser.DeallocateStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#withQuery}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWithQuery(SimplifiedSqlParser.WithQueryContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#cteDef}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCteDef(SimplifiedSqlParser.CteDefContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#createViewStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCreateViewStmt(SimplifiedSqlParser.CreateViewStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#createFunctionStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCreateFunctionStmt(SimplifiedSqlParser.CreateFunctionStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#funcParam}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFuncParam(SimplifiedSqlParser.FuncParamContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#tableFuncCol}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTableFuncCol(SimplifiedSqlParser.TableFuncColContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#dropFunctionStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDropFunctionStmt(SimplifiedSqlParser.DropFunctionStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#createTriggerStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCreateTriggerStmt(SimplifiedSqlParser.CreateTriggerStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#dropTriggerStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDropTriggerStmt(SimplifiedSqlParser.DropTriggerStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#dropViewStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDropViewStmt(SimplifiedSqlParser.DropViewStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#createMaterializedViewStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCreateMaterializedViewStmt(SimplifiedSqlParser.CreateMaterializedViewStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#refreshMaterializedViewStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRefreshMaterializedViewStmt(SimplifiedSqlParser.RefreshMaterializedViewStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#query}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitQuery(SimplifiedSqlParser.QueryContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#unionTail}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUnionTail(SimplifiedSqlParser.UnionTailContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#setOperator}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSetOperator(SimplifiedSqlParser.SetOperatorContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#selectQuery}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSelectQuery(SimplifiedSqlParser.SelectQueryContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#selectExprQuery}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSelectExprQuery(SimplifiedSqlParser.SelectExprQueryContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#groupByList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGroupByList(SimplifiedSqlParser.GroupByListContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#forUpdateClause}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitForUpdateClause(SimplifiedSqlParser.ForUpdateClauseContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#windowClause}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWindowClause(SimplifiedSqlParser.WindowClauseContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#windowDef}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWindowDef(SimplifiedSqlParser.WindowDefContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#windowSpec}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWindowSpec(SimplifiedSqlParser.WindowSpecContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#partitionByList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPartitionByList(SimplifiedSqlParser.PartitionByListContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#fromItem}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFromItem(SimplifiedSqlParser.FromItemContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#explainStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExplainStmt(SimplifiedSqlParser.ExplainStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#explainBody}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExplainBody(SimplifiedSqlParser.ExplainBodyContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#analyzeStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAnalyzeStmt(SimplifiedSqlParser.AnalyzeStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#insertStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInsertStmt(SimplifiedSqlParser.InsertStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#returningClause}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitReturningClause(SimplifiedSqlParser.ReturningClauseContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#onConflictClause}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOnConflictClause(SimplifiedSqlParser.OnConflictClauseContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#conflictAction}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConflictAction(SimplifiedSqlParser.ConflictActionContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#mergeStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMergeStmt(SimplifiedSqlParser.MergeStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#mergeSource}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMergeSource(SimplifiedSqlParser.MergeSourceContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#whenMatchedClause}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWhenMatchedClause(SimplifiedSqlParser.WhenMatchedClauseContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#whenNotMatchedClause}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWhenNotMatchedClause(SimplifiedSqlParser.WhenNotMatchedClauseContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#insertColumnList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInsertColumnList(SimplifiedSqlParser.InsertColumnListContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#valueTuple}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitValueTuple(SimplifiedSqlParser.ValueTupleContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#deleteStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDeleteStmt(SimplifiedSqlParser.DeleteStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#updateStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUpdateStmt(SimplifiedSqlParser.UpdateStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#updateAssign}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUpdateAssign(SimplifiedSqlParser.UpdateAssignContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#updateRhs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUpdateRhs(SimplifiedSqlParser.UpdateRhsContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#createTableStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCreateTableStmt(SimplifiedSqlParser.CreateTableStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#tableElement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTableElement(SimplifiedSqlParser.TableElementContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#columnDef}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitColumnDef(SimplifiedSqlParser.ColumnDefContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#identityClause}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIdentityClause(SimplifiedSqlParser.IdentityClauseContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#serialType}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSerialType(SimplifiedSqlParser.SerialTypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#constraintName}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConstraintName(SimplifiedSqlParser.ConstraintNameContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#referentialAction}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitReferentialAction(SimplifiedSqlParser.ReferentialActionContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#typeName}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTypeName(SimplifiedSqlParser.TypeNameContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#createSequenceStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCreateSequenceStmt(SimplifiedSqlParser.CreateSequenceStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#dropSequenceStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDropSequenceStmt(SimplifiedSqlParser.DropSequenceStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#selectSequenceStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSelectSequenceStmt(SimplifiedSqlParser.SelectSequenceStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#sequenceCall}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSequenceCall(SimplifiedSqlParser.SequenceCallContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#sequenceName}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSequenceName(SimplifiedSqlParser.SequenceNameContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#sequenceNameArg}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSequenceNameArg(SimplifiedSqlParser.SequenceNameArgContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#dropTableStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDropTableStmt(SimplifiedSqlParser.DropTableStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#createIndexStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCreateIndexStmt(SimplifiedSqlParser.CreateIndexStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#dropIndexStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDropIndexStmt(SimplifiedSqlParser.DropIndexStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#indexName}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIndexName(SimplifiedSqlParser.IndexNameContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#joinClause}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitJoinClause(SimplifiedSqlParser.JoinClauseContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#joinHead}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitJoinHead(SimplifiedSqlParser.JoinHeadContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#joinTarget}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitJoinTarget(SimplifiedSqlParser.JoinTargetContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#joinCond}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitJoinCond(SimplifiedSqlParser.JoinCondContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#selectList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSelectList(SimplifiedSqlParser.SelectListContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#selectItem}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSelectItem(SimplifiedSqlParser.SelectItemContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#functionCall}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFunctionCall(SimplifiedSqlParser.FunctionCallContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#funcArg}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFuncArg(SimplifiedSqlParser.FuncArgContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#aggregateExpr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAggregateExpr(SimplifiedSqlParser.AggregateExprContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#windowExpr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWindowExpr(SimplifiedSqlParser.WindowExprContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#overClause}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOverClause(SimplifiedSqlParser.OverClauseContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#columnList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitColumnList(SimplifiedSqlParser.ColumnListContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#columnName}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitColumnName(SimplifiedSqlParser.ColumnNameContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#tableName}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTableName(SimplifiedSqlParser.TableNameContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#ident}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIdent(SimplifiedSqlParser.IdentContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#keywordAsIdent}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitKeywordAsIdent(SimplifiedSqlParser.KeywordAsIdentContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#createSchemaStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCreateSchemaStmt(SimplifiedSqlParser.CreateSchemaStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#dropSchemaStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDropSchemaStmt(SimplifiedSqlParser.DropSchemaStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#setSchemaStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSetSchemaStmt(SimplifiedSqlParser.SetSchemaStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#setRemoteDirtyStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSetRemoteDirtyStmt(SimplifiedSqlParser.SetRemoteDirtyStmtContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#alterTableStmt}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAlterTableStmt(SimplifiedSqlParser.AlterTableStmtContext ctx);
	/**
	 * Visit a parse tree produced by the {@code AndExpression}
	 * labeled alternative in {@link SimplifiedSqlParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAndExpression(SimplifiedSqlParser.AndExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ParenExpression}
	 * labeled alternative in {@link SimplifiedSqlParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitParenExpression(SimplifiedSqlParser.ParenExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code TrueOrFalseExpression}
	 * labeled alternative in {@link SimplifiedSqlParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTrueOrFalseExpression(SimplifiedSqlParser.TrueOrFalseExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code NotExpression}
	 * labeled alternative in {@link SimplifiedSqlParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNotExpression(SimplifiedSqlParser.NotExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PredicateExpression}
	 * labeled alternative in {@link SimplifiedSqlParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPredicateExpression(SimplifiedSqlParser.PredicateExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code OrExpression}
	 * labeled alternative in {@link SimplifiedSqlParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOrExpression(SimplifiedSqlParser.OrExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code Comparison}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitComparison(SimplifiedSqlParser.ComparisonContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ColumnComparison}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitColumnComparison(SimplifiedSqlParser.ColumnComparisonContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ComparisonSubquery}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitComparisonSubquery(SimplifiedSqlParser.ComparisonSubqueryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code FunctionComparison}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFunctionComparison(SimplifiedSqlParser.FunctionComparisonContext ctx);
	/**
	 * Visit a parse tree produced by the {@code AggComparison}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAggComparison(SimplifiedSqlParser.AggComparisonContext ctx);
	/**
	 * Visit a parse tree produced by the {@code Between}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBetween(SimplifiedSqlParser.BetweenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code In}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIn(SimplifiedSqlParser.InContext ctx);
	/**
	 * Visit a parse tree produced by the {@code InSubquery}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInSubquery(SimplifiedSqlParser.InSubqueryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ExistsSubquery}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExistsSubquery(SimplifiedSqlParser.ExistsSubqueryContext ctx);
	/**
	 * Visit a parse tree produced by the {@code Like}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLike(SimplifiedSqlParser.LikeContext ctx);
	/**
	 * Visit a parse tree produced by the {@code IsNull}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIsNull(SimplifiedSqlParser.IsNullContext ctx);
	/**
	 * Visit a parse tree produced by the {@code IsNotNull}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIsNotNull(SimplifiedSqlParser.IsNotNullContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#trueFalseExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTrueFalseExpression(SimplifiedSqlParser.TrueFalseExpressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#valueList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitValueList(SimplifiedSqlParser.ValueListContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#operator}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOperator(SimplifiedSqlParser.OperatorContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#value}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitValue(SimplifiedSqlParser.ValueContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#oldNewRef}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOldNewRef(SimplifiedSqlParser.OldNewRefContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#caseExpr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCaseExpr(SimplifiedSqlParser.CaseExprContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#orderList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOrderList(SimplifiedSqlParser.OrderListContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#orderItem}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOrderItem(SimplifiedSqlParser.OrderItemContext ctx);
	/**
	 * Visit a parse tree produced by {@link SimplifiedSqlParser#limitClause}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLimitClause(SimplifiedSqlParser.LimitClauseContext ctx);
}