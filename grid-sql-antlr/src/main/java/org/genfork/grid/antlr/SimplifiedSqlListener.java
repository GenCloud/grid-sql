// Generated from SimplifiedSql.g4 by ANTLR 4.13.2
package org.genfork.grid.antlr;
import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link SimplifiedSqlParser}.
 */
public interface SimplifiedSqlListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#statement}.
	 * @param ctx the parse tree
	 */
	void enterStatement(SimplifiedSqlParser.StatementContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#statement}.
	 * @param ctx the parse tree
	 */
	void exitStatement(SimplifiedSqlParser.StatementContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#script}.
	 * @param ctx the parse tree
	 */
	void enterScript(SimplifiedSqlParser.ScriptContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#script}.
	 * @param ctx the parse tree
	 */
	void exitScript(SimplifiedSqlParser.ScriptContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#executable}.
	 * @param ctx the parse tree
	 */
	void enterExecutable(SimplifiedSqlParser.ExecutableContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#executable}.
	 * @param ctx the parse tree
	 */
	void exitExecutable(SimplifiedSqlParser.ExecutableContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#createUserStmt}.
	 * @param ctx the parse tree
	 */
	void enterCreateUserStmt(SimplifiedSqlParser.CreateUserStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#createUserStmt}.
	 * @param ctx the parse tree
	 */
	void exitCreateUserStmt(SimplifiedSqlParser.CreateUserStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#dropUserStmt}.
	 * @param ctx the parse tree
	 */
	void enterDropUserStmt(SimplifiedSqlParser.DropUserStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#dropUserStmt}.
	 * @param ctx the parse tree
	 */
	void exitDropUserStmt(SimplifiedSqlParser.DropUserStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#alterUserStmt}.
	 * @param ctx the parse tree
	 */
	void enterAlterUserStmt(SimplifiedSqlParser.AlterUserStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#alterUserStmt}.
	 * @param ctx the parse tree
	 */
	void exitAlterUserStmt(SimplifiedSqlParser.AlterUserStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#createRoleStmt}.
	 * @param ctx the parse tree
	 */
	void enterCreateRoleStmt(SimplifiedSqlParser.CreateRoleStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#createRoleStmt}.
	 * @param ctx the parse tree
	 */
	void exitCreateRoleStmt(SimplifiedSqlParser.CreateRoleStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#dropRoleStmt}.
	 * @param ctx the parse tree
	 */
	void enterDropRoleStmt(SimplifiedSqlParser.DropRoleStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#dropRoleStmt}.
	 * @param ctx the parse tree
	 */
	void exitDropRoleStmt(SimplifiedSqlParser.DropRoleStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#grantStmt}.
	 * @param ctx the parse tree
	 */
	void enterGrantStmt(SimplifiedSqlParser.GrantStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#grantStmt}.
	 * @param ctx the parse tree
	 */
	void exitGrantStmt(SimplifiedSqlParser.GrantStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#revokeStmt}.
	 * @param ctx the parse tree
	 */
	void enterRevokeStmt(SimplifiedSqlParser.RevokeStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#revokeStmt}.
	 * @param ctx the parse tree
	 */
	void exitRevokeStmt(SimplifiedSqlParser.RevokeStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#privilegeList}.
	 * @param ctx the parse tree
	 */
	void enterPrivilegeList(SimplifiedSqlParser.PrivilegeListContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#privilegeList}.
	 * @param ctx the parse tree
	 */
	void exitPrivilegeList(SimplifiedSqlParser.PrivilegeListContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#privilegeName}.
	 * @param ctx the parse tree
	 */
	void enterPrivilegeName(SimplifiedSqlParser.PrivilegeNameContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#privilegeName}.
	 * @param ctx the parse tree
	 */
	void exitPrivilegeName(SimplifiedSqlParser.PrivilegeNameContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#privilegeTarget}.
	 * @param ctx the parse tree
	 */
	void enterPrivilegeTarget(SimplifiedSqlParser.PrivilegeTargetContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#privilegeTarget}.
	 * @param ctx the parse tree
	 */
	void exitPrivilegeTarget(SimplifiedSqlParser.PrivilegeTargetContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#pinStmt}.
	 * @param ctx the parse tree
	 */
	void enterPinStmt(SimplifiedSqlParser.PinStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#pinStmt}.
	 * @param ctx the parse tree
	 */
	void exitPinStmt(SimplifiedSqlParser.PinStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#unpinStmt}.
	 * @param ctx the parse tree
	 */
	void enterUnpinStmt(SimplifiedSqlParser.UnpinStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#unpinStmt}.
	 * @param ctx the parse tree
	 */
	void exitUnpinStmt(SimplifiedSqlParser.UnpinStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#beginStmt}.
	 * @param ctx the parse tree
	 */
	void enterBeginStmt(SimplifiedSqlParser.BeginStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#beginStmt}.
	 * @param ctx the parse tree
	 */
	void exitBeginStmt(SimplifiedSqlParser.BeginStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#commitStmt}.
	 * @param ctx the parse tree
	 */
	void enterCommitStmt(SimplifiedSqlParser.CommitStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#commitStmt}.
	 * @param ctx the parse tree
	 */
	void exitCommitStmt(SimplifiedSqlParser.CommitStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#rollbackStmt}.
	 * @param ctx the parse tree
	 */
	void enterRollbackStmt(SimplifiedSqlParser.RollbackStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#rollbackStmt}.
	 * @param ctx the parse tree
	 */
	void exitRollbackStmt(SimplifiedSqlParser.RollbackStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#savepointStmt}.
	 * @param ctx the parse tree
	 */
	void enterSavepointStmt(SimplifiedSqlParser.SavepointStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#savepointStmt}.
	 * @param ctx the parse tree
	 */
	void exitSavepointStmt(SimplifiedSqlParser.SavepointStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#rollbackToSavepointStmt}.
	 * @param ctx the parse tree
	 */
	void enterRollbackToSavepointStmt(SimplifiedSqlParser.RollbackToSavepointStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#rollbackToSavepointStmt}.
	 * @param ctx the parse tree
	 */
	void exitRollbackToSavepointStmt(SimplifiedSqlParser.RollbackToSavepointStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#releaseSavepointStmt}.
	 * @param ctx the parse tree
	 */
	void enterReleaseSavepointStmt(SimplifiedSqlParser.ReleaseSavepointStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#releaseSavepointStmt}.
	 * @param ctx the parse tree
	 */
	void exitReleaseSavepointStmt(SimplifiedSqlParser.ReleaseSavepointStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#prepareStmt}.
	 * @param ctx the parse tree
	 */
	void enterPrepareStmt(SimplifiedSqlParser.PrepareStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#prepareStmt}.
	 * @param ctx the parse tree
	 */
	void exitPrepareStmt(SimplifiedSqlParser.PrepareStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#executeStmt}.
	 * @param ctx the parse tree
	 */
	void enterExecuteStmt(SimplifiedSqlParser.ExecuteStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#executeStmt}.
	 * @param ctx the parse tree
	 */
	void exitExecuteStmt(SimplifiedSqlParser.ExecuteStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#deallocateStmt}.
	 * @param ctx the parse tree
	 */
	void enterDeallocateStmt(SimplifiedSqlParser.DeallocateStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#deallocateStmt}.
	 * @param ctx the parse tree
	 */
	void exitDeallocateStmt(SimplifiedSqlParser.DeallocateStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#withQuery}.
	 * @param ctx the parse tree
	 */
	void enterWithQuery(SimplifiedSqlParser.WithQueryContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#withQuery}.
	 * @param ctx the parse tree
	 */
	void exitWithQuery(SimplifiedSqlParser.WithQueryContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#cteDef}.
	 * @param ctx the parse tree
	 */
	void enterCteDef(SimplifiedSqlParser.CteDefContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#cteDef}.
	 * @param ctx the parse tree
	 */
	void exitCteDef(SimplifiedSqlParser.CteDefContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#createViewStmt}.
	 * @param ctx the parse tree
	 */
	void enterCreateViewStmt(SimplifiedSqlParser.CreateViewStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#createViewStmt}.
	 * @param ctx the parse tree
	 */
	void exitCreateViewStmt(SimplifiedSqlParser.CreateViewStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#createFunctionStmt}.
	 * @param ctx the parse tree
	 */
	void enterCreateFunctionStmt(SimplifiedSqlParser.CreateFunctionStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#createFunctionStmt}.
	 * @param ctx the parse tree
	 */
	void exitCreateFunctionStmt(SimplifiedSqlParser.CreateFunctionStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#funcParam}.
	 * @param ctx the parse tree
	 */
	void enterFuncParam(SimplifiedSqlParser.FuncParamContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#funcParam}.
	 * @param ctx the parse tree
	 */
	void exitFuncParam(SimplifiedSqlParser.FuncParamContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#tableFuncCol}.
	 * @param ctx the parse tree
	 */
	void enterTableFuncCol(SimplifiedSqlParser.TableFuncColContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#tableFuncCol}.
	 * @param ctx the parse tree
	 */
	void exitTableFuncCol(SimplifiedSqlParser.TableFuncColContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#dropFunctionStmt}.
	 * @param ctx the parse tree
	 */
	void enterDropFunctionStmt(SimplifiedSqlParser.DropFunctionStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#dropFunctionStmt}.
	 * @param ctx the parse tree
	 */
	void exitDropFunctionStmt(SimplifiedSqlParser.DropFunctionStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#createTriggerStmt}.
	 * @param ctx the parse tree
	 */
	void enterCreateTriggerStmt(SimplifiedSqlParser.CreateTriggerStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#createTriggerStmt}.
	 * @param ctx the parse tree
	 */
	void exitCreateTriggerStmt(SimplifiedSqlParser.CreateTriggerStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#dropTriggerStmt}.
	 * @param ctx the parse tree
	 */
	void enterDropTriggerStmt(SimplifiedSqlParser.DropTriggerStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#dropTriggerStmt}.
	 * @param ctx the parse tree
	 */
	void exitDropTriggerStmt(SimplifiedSqlParser.DropTriggerStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#dropViewStmt}.
	 * @param ctx the parse tree
	 */
	void enterDropViewStmt(SimplifiedSqlParser.DropViewStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#dropViewStmt}.
	 * @param ctx the parse tree
	 */
	void exitDropViewStmt(SimplifiedSqlParser.DropViewStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#createMaterializedViewStmt}.
	 * @param ctx the parse tree
	 */
	void enterCreateMaterializedViewStmt(SimplifiedSqlParser.CreateMaterializedViewStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#createMaterializedViewStmt}.
	 * @param ctx the parse tree
	 */
	void exitCreateMaterializedViewStmt(SimplifiedSqlParser.CreateMaterializedViewStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#refreshMaterializedViewStmt}.
	 * @param ctx the parse tree
	 */
	void enterRefreshMaterializedViewStmt(SimplifiedSqlParser.RefreshMaterializedViewStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#refreshMaterializedViewStmt}.
	 * @param ctx the parse tree
	 */
	void exitRefreshMaterializedViewStmt(SimplifiedSqlParser.RefreshMaterializedViewStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#query}.
	 * @param ctx the parse tree
	 */
	void enterQuery(SimplifiedSqlParser.QueryContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#query}.
	 * @param ctx the parse tree
	 */
	void exitQuery(SimplifiedSqlParser.QueryContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#unionTail}.
	 * @param ctx the parse tree
	 */
	void enterUnionTail(SimplifiedSqlParser.UnionTailContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#unionTail}.
	 * @param ctx the parse tree
	 */
	void exitUnionTail(SimplifiedSqlParser.UnionTailContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#setOperator}.
	 * @param ctx the parse tree
	 */
	void enterSetOperator(SimplifiedSqlParser.SetOperatorContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#setOperator}.
	 * @param ctx the parse tree
	 */
	void exitSetOperator(SimplifiedSqlParser.SetOperatorContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#selectQuery}.
	 * @param ctx the parse tree
	 */
	void enterSelectQuery(SimplifiedSqlParser.SelectQueryContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#selectQuery}.
	 * @param ctx the parse tree
	 */
	void exitSelectQuery(SimplifiedSqlParser.SelectQueryContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#selectExprQuery}.
	 * @param ctx the parse tree
	 */
	void enterSelectExprQuery(SimplifiedSqlParser.SelectExprQueryContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#selectExprQuery}.
	 * @param ctx the parse tree
	 */
	void exitSelectExprQuery(SimplifiedSqlParser.SelectExprQueryContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#groupByList}.
	 * @param ctx the parse tree
	 */
	void enterGroupByList(SimplifiedSqlParser.GroupByListContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#groupByList}.
	 * @param ctx the parse tree
	 */
	void exitGroupByList(SimplifiedSqlParser.GroupByListContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#forUpdateClause}.
	 * @param ctx the parse tree
	 */
	void enterForUpdateClause(SimplifiedSqlParser.ForUpdateClauseContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#forUpdateClause}.
	 * @param ctx the parse tree
	 */
	void exitForUpdateClause(SimplifiedSqlParser.ForUpdateClauseContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#windowClause}.
	 * @param ctx the parse tree
	 */
	void enterWindowClause(SimplifiedSqlParser.WindowClauseContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#windowClause}.
	 * @param ctx the parse tree
	 */
	void exitWindowClause(SimplifiedSqlParser.WindowClauseContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#windowDef}.
	 * @param ctx the parse tree
	 */
	void enterWindowDef(SimplifiedSqlParser.WindowDefContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#windowDef}.
	 * @param ctx the parse tree
	 */
	void exitWindowDef(SimplifiedSqlParser.WindowDefContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#windowSpec}.
	 * @param ctx the parse tree
	 */
	void enterWindowSpec(SimplifiedSqlParser.WindowSpecContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#windowSpec}.
	 * @param ctx the parse tree
	 */
	void exitWindowSpec(SimplifiedSqlParser.WindowSpecContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#partitionByList}.
	 * @param ctx the parse tree
	 */
	void enterPartitionByList(SimplifiedSqlParser.PartitionByListContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#partitionByList}.
	 * @param ctx the parse tree
	 */
	void exitPartitionByList(SimplifiedSqlParser.PartitionByListContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#fromItem}.
	 * @param ctx the parse tree
	 */
	void enterFromItem(SimplifiedSqlParser.FromItemContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#fromItem}.
	 * @param ctx the parse tree
	 */
	void exitFromItem(SimplifiedSqlParser.FromItemContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#explainStmt}.
	 * @param ctx the parse tree
	 */
	void enterExplainStmt(SimplifiedSqlParser.ExplainStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#explainStmt}.
	 * @param ctx the parse tree
	 */
	void exitExplainStmt(SimplifiedSqlParser.ExplainStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#explainBody}.
	 * @param ctx the parse tree
	 */
	void enterExplainBody(SimplifiedSqlParser.ExplainBodyContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#explainBody}.
	 * @param ctx the parse tree
	 */
	void exitExplainBody(SimplifiedSqlParser.ExplainBodyContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#analyzeStmt}.
	 * @param ctx the parse tree
	 */
	void enterAnalyzeStmt(SimplifiedSqlParser.AnalyzeStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#analyzeStmt}.
	 * @param ctx the parse tree
	 */
	void exitAnalyzeStmt(SimplifiedSqlParser.AnalyzeStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#insertStmt}.
	 * @param ctx the parse tree
	 */
	void enterInsertStmt(SimplifiedSqlParser.InsertStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#insertStmt}.
	 * @param ctx the parse tree
	 */
	void exitInsertStmt(SimplifiedSqlParser.InsertStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#returningClause}.
	 * @param ctx the parse tree
	 */
	void enterReturningClause(SimplifiedSqlParser.ReturningClauseContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#returningClause}.
	 * @param ctx the parse tree
	 */
	void exitReturningClause(SimplifiedSqlParser.ReturningClauseContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#onConflictClause}.
	 * @param ctx the parse tree
	 */
	void enterOnConflictClause(SimplifiedSqlParser.OnConflictClauseContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#onConflictClause}.
	 * @param ctx the parse tree
	 */
	void exitOnConflictClause(SimplifiedSqlParser.OnConflictClauseContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#conflictAction}.
	 * @param ctx the parse tree
	 */
	void enterConflictAction(SimplifiedSqlParser.ConflictActionContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#conflictAction}.
	 * @param ctx the parse tree
	 */
	void exitConflictAction(SimplifiedSqlParser.ConflictActionContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#mergeStmt}.
	 * @param ctx the parse tree
	 */
	void enterMergeStmt(SimplifiedSqlParser.MergeStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#mergeStmt}.
	 * @param ctx the parse tree
	 */
	void exitMergeStmt(SimplifiedSqlParser.MergeStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#mergeSource}.
	 * @param ctx the parse tree
	 */
	void enterMergeSource(SimplifiedSqlParser.MergeSourceContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#mergeSource}.
	 * @param ctx the parse tree
	 */
	void exitMergeSource(SimplifiedSqlParser.MergeSourceContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#whenMatchedClause}.
	 * @param ctx the parse tree
	 */
	void enterWhenMatchedClause(SimplifiedSqlParser.WhenMatchedClauseContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#whenMatchedClause}.
	 * @param ctx the parse tree
	 */
	void exitWhenMatchedClause(SimplifiedSqlParser.WhenMatchedClauseContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#whenNotMatchedClause}.
	 * @param ctx the parse tree
	 */
	void enterWhenNotMatchedClause(SimplifiedSqlParser.WhenNotMatchedClauseContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#whenNotMatchedClause}.
	 * @param ctx the parse tree
	 */
	void exitWhenNotMatchedClause(SimplifiedSqlParser.WhenNotMatchedClauseContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#insertColumnList}.
	 * @param ctx the parse tree
	 */
	void enterInsertColumnList(SimplifiedSqlParser.InsertColumnListContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#insertColumnList}.
	 * @param ctx the parse tree
	 */
	void exitInsertColumnList(SimplifiedSqlParser.InsertColumnListContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#valueTuple}.
	 * @param ctx the parse tree
	 */
	void enterValueTuple(SimplifiedSqlParser.ValueTupleContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#valueTuple}.
	 * @param ctx the parse tree
	 */
	void exitValueTuple(SimplifiedSqlParser.ValueTupleContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#deleteStmt}.
	 * @param ctx the parse tree
	 */
	void enterDeleteStmt(SimplifiedSqlParser.DeleteStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#deleteStmt}.
	 * @param ctx the parse tree
	 */
	void exitDeleteStmt(SimplifiedSqlParser.DeleteStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#updateStmt}.
	 * @param ctx the parse tree
	 */
	void enterUpdateStmt(SimplifiedSqlParser.UpdateStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#updateStmt}.
	 * @param ctx the parse tree
	 */
	void exitUpdateStmt(SimplifiedSqlParser.UpdateStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#updateAssign}.
	 * @param ctx the parse tree
	 */
	void enterUpdateAssign(SimplifiedSqlParser.UpdateAssignContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#updateAssign}.
	 * @param ctx the parse tree
	 */
	void exitUpdateAssign(SimplifiedSqlParser.UpdateAssignContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#updateRhs}.
	 * @param ctx the parse tree
	 */
	void enterUpdateRhs(SimplifiedSqlParser.UpdateRhsContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#updateRhs}.
	 * @param ctx the parse tree
	 */
	void exitUpdateRhs(SimplifiedSqlParser.UpdateRhsContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#createTableStmt}.
	 * @param ctx the parse tree
	 */
	void enterCreateTableStmt(SimplifiedSqlParser.CreateTableStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#createTableStmt}.
	 * @param ctx the parse tree
	 */
	void exitCreateTableStmt(SimplifiedSqlParser.CreateTableStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#tableElement}.
	 * @param ctx the parse tree
	 */
	void enterTableElement(SimplifiedSqlParser.TableElementContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#tableElement}.
	 * @param ctx the parse tree
	 */
	void exitTableElement(SimplifiedSqlParser.TableElementContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#columnDef}.
	 * @param ctx the parse tree
	 */
	void enterColumnDef(SimplifiedSqlParser.ColumnDefContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#columnDef}.
	 * @param ctx the parse tree
	 */
	void exitColumnDef(SimplifiedSqlParser.ColumnDefContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#identityClause}.
	 * @param ctx the parse tree
	 */
	void enterIdentityClause(SimplifiedSqlParser.IdentityClauseContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#identityClause}.
	 * @param ctx the parse tree
	 */
	void exitIdentityClause(SimplifiedSqlParser.IdentityClauseContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#serialType}.
	 * @param ctx the parse tree
	 */
	void enterSerialType(SimplifiedSqlParser.SerialTypeContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#serialType}.
	 * @param ctx the parse tree
	 */
	void exitSerialType(SimplifiedSqlParser.SerialTypeContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#constraintName}.
	 * @param ctx the parse tree
	 */
	void enterConstraintName(SimplifiedSqlParser.ConstraintNameContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#constraintName}.
	 * @param ctx the parse tree
	 */
	void exitConstraintName(SimplifiedSqlParser.ConstraintNameContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#referentialAction}.
	 * @param ctx the parse tree
	 */
	void enterReferentialAction(SimplifiedSqlParser.ReferentialActionContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#referentialAction}.
	 * @param ctx the parse tree
	 */
	void exitReferentialAction(SimplifiedSqlParser.ReferentialActionContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#typeName}.
	 * @param ctx the parse tree
	 */
	void enterTypeName(SimplifiedSqlParser.TypeNameContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#typeName}.
	 * @param ctx the parse tree
	 */
	void exitTypeName(SimplifiedSqlParser.TypeNameContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#createSequenceStmt}.
	 * @param ctx the parse tree
	 */
	void enterCreateSequenceStmt(SimplifiedSqlParser.CreateSequenceStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#createSequenceStmt}.
	 * @param ctx the parse tree
	 */
	void exitCreateSequenceStmt(SimplifiedSqlParser.CreateSequenceStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#dropSequenceStmt}.
	 * @param ctx the parse tree
	 */
	void enterDropSequenceStmt(SimplifiedSqlParser.DropSequenceStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#dropSequenceStmt}.
	 * @param ctx the parse tree
	 */
	void exitDropSequenceStmt(SimplifiedSqlParser.DropSequenceStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#selectSequenceStmt}.
	 * @param ctx the parse tree
	 */
	void enterSelectSequenceStmt(SimplifiedSqlParser.SelectSequenceStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#selectSequenceStmt}.
	 * @param ctx the parse tree
	 */
	void exitSelectSequenceStmt(SimplifiedSqlParser.SelectSequenceStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#sequenceCall}.
	 * @param ctx the parse tree
	 */
	void enterSequenceCall(SimplifiedSqlParser.SequenceCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#sequenceCall}.
	 * @param ctx the parse tree
	 */
	void exitSequenceCall(SimplifiedSqlParser.SequenceCallContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#sequenceName}.
	 * @param ctx the parse tree
	 */
	void enterSequenceName(SimplifiedSqlParser.SequenceNameContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#sequenceName}.
	 * @param ctx the parse tree
	 */
	void exitSequenceName(SimplifiedSqlParser.SequenceNameContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#sequenceNameArg}.
	 * @param ctx the parse tree
	 */
	void enterSequenceNameArg(SimplifiedSqlParser.SequenceNameArgContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#sequenceNameArg}.
	 * @param ctx the parse tree
	 */
	void exitSequenceNameArg(SimplifiedSqlParser.SequenceNameArgContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#dropTableStmt}.
	 * @param ctx the parse tree
	 */
	void enterDropTableStmt(SimplifiedSqlParser.DropTableStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#dropTableStmt}.
	 * @param ctx the parse tree
	 */
	void exitDropTableStmt(SimplifiedSqlParser.DropTableStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#createIndexStmt}.
	 * @param ctx the parse tree
	 */
	void enterCreateIndexStmt(SimplifiedSqlParser.CreateIndexStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#createIndexStmt}.
	 * @param ctx the parse tree
	 */
	void exitCreateIndexStmt(SimplifiedSqlParser.CreateIndexStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#dropIndexStmt}.
	 * @param ctx the parse tree
	 */
	void enterDropIndexStmt(SimplifiedSqlParser.DropIndexStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#dropIndexStmt}.
	 * @param ctx the parse tree
	 */
	void exitDropIndexStmt(SimplifiedSqlParser.DropIndexStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#indexName}.
	 * @param ctx the parse tree
	 */
	void enterIndexName(SimplifiedSqlParser.IndexNameContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#indexName}.
	 * @param ctx the parse tree
	 */
	void exitIndexName(SimplifiedSqlParser.IndexNameContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#joinClause}.
	 * @param ctx the parse tree
	 */
	void enterJoinClause(SimplifiedSqlParser.JoinClauseContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#joinClause}.
	 * @param ctx the parse tree
	 */
	void exitJoinClause(SimplifiedSqlParser.JoinClauseContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#selectList}.
	 * @param ctx the parse tree
	 */
	void enterSelectList(SimplifiedSqlParser.SelectListContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#selectList}.
	 * @param ctx the parse tree
	 */
	void exitSelectList(SimplifiedSqlParser.SelectListContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#selectItem}.
	 * @param ctx the parse tree
	 */
	void enterSelectItem(SimplifiedSqlParser.SelectItemContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#selectItem}.
	 * @param ctx the parse tree
	 */
	void exitSelectItem(SimplifiedSqlParser.SelectItemContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#functionCall}.
	 * @param ctx the parse tree
	 */
	void enterFunctionCall(SimplifiedSqlParser.FunctionCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#functionCall}.
	 * @param ctx the parse tree
	 */
	void exitFunctionCall(SimplifiedSqlParser.FunctionCallContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#funcArg}.
	 * @param ctx the parse tree
	 */
	void enterFuncArg(SimplifiedSqlParser.FuncArgContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#funcArg}.
	 * @param ctx the parse tree
	 */
	void exitFuncArg(SimplifiedSqlParser.FuncArgContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#aggregateExpr}.
	 * @param ctx the parse tree
	 */
	void enterAggregateExpr(SimplifiedSqlParser.AggregateExprContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#aggregateExpr}.
	 * @param ctx the parse tree
	 */
	void exitAggregateExpr(SimplifiedSqlParser.AggregateExprContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#windowExpr}.
	 * @param ctx the parse tree
	 */
	void enterWindowExpr(SimplifiedSqlParser.WindowExprContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#windowExpr}.
	 * @param ctx the parse tree
	 */
	void exitWindowExpr(SimplifiedSqlParser.WindowExprContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#overClause}.
	 * @param ctx the parse tree
	 */
	void enterOverClause(SimplifiedSqlParser.OverClauseContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#overClause}.
	 * @param ctx the parse tree
	 */
	void exitOverClause(SimplifiedSqlParser.OverClauseContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#columnList}.
	 * @param ctx the parse tree
	 */
	void enterColumnList(SimplifiedSqlParser.ColumnListContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#columnList}.
	 * @param ctx the parse tree
	 */
	void exitColumnList(SimplifiedSqlParser.ColumnListContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#columnName}.
	 * @param ctx the parse tree
	 */
	void enterColumnName(SimplifiedSqlParser.ColumnNameContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#columnName}.
	 * @param ctx the parse tree
	 */
	void exitColumnName(SimplifiedSqlParser.ColumnNameContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#tableName}.
	 * @param ctx the parse tree
	 */
	void enterTableName(SimplifiedSqlParser.TableNameContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#tableName}.
	 * @param ctx the parse tree
	 */
	void exitTableName(SimplifiedSqlParser.TableNameContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#createSchemaStmt}.
	 * @param ctx the parse tree
	 */
	void enterCreateSchemaStmt(SimplifiedSqlParser.CreateSchemaStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#createSchemaStmt}.
	 * @param ctx the parse tree
	 */
	void exitCreateSchemaStmt(SimplifiedSqlParser.CreateSchemaStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#dropSchemaStmt}.
	 * @param ctx the parse tree
	 */
	void enterDropSchemaStmt(SimplifiedSqlParser.DropSchemaStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#dropSchemaStmt}.
	 * @param ctx the parse tree
	 */
	void exitDropSchemaStmt(SimplifiedSqlParser.DropSchemaStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#setSchemaStmt}.
	 * @param ctx the parse tree
	 */
	void enterSetSchemaStmt(SimplifiedSqlParser.SetSchemaStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#setSchemaStmt}.
	 * @param ctx the parse tree
	 */
	void exitSetSchemaStmt(SimplifiedSqlParser.SetSchemaStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#setRemoteDirtyStmt}.
	 * @param ctx the parse tree
	 */
	void enterSetRemoteDirtyStmt(SimplifiedSqlParser.SetRemoteDirtyStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#setRemoteDirtyStmt}.
	 * @param ctx the parse tree
	 */
	void exitSetRemoteDirtyStmt(SimplifiedSqlParser.SetRemoteDirtyStmtContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#alterTableStmt}.
	 * @param ctx the parse tree
	 */
	void enterAlterTableStmt(SimplifiedSqlParser.AlterTableStmtContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#alterTableStmt}.
	 * @param ctx the parse tree
	 */
	void exitAlterTableStmt(SimplifiedSqlParser.AlterTableStmtContext ctx);
	/**
	 * Enter a parse tree produced by the {@code AndExpression}
	 * labeled alternative in {@link SimplifiedSqlParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterAndExpression(SimplifiedSqlParser.AndExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code AndExpression}
	 * labeled alternative in {@link SimplifiedSqlParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitAndExpression(SimplifiedSqlParser.AndExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ParenExpression}
	 * labeled alternative in {@link SimplifiedSqlParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterParenExpression(SimplifiedSqlParser.ParenExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ParenExpression}
	 * labeled alternative in {@link SimplifiedSqlParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitParenExpression(SimplifiedSqlParser.ParenExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code TrueOrFalseExpression}
	 * labeled alternative in {@link SimplifiedSqlParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterTrueOrFalseExpression(SimplifiedSqlParser.TrueOrFalseExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code TrueOrFalseExpression}
	 * labeled alternative in {@link SimplifiedSqlParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitTrueOrFalseExpression(SimplifiedSqlParser.TrueOrFalseExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code NotExpression}
	 * labeled alternative in {@link SimplifiedSqlParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterNotExpression(SimplifiedSqlParser.NotExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code NotExpression}
	 * labeled alternative in {@link SimplifiedSqlParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitNotExpression(SimplifiedSqlParser.NotExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code PredicateExpression}
	 * labeled alternative in {@link SimplifiedSqlParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterPredicateExpression(SimplifiedSqlParser.PredicateExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code PredicateExpression}
	 * labeled alternative in {@link SimplifiedSqlParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitPredicateExpression(SimplifiedSqlParser.PredicateExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code OrExpression}
	 * labeled alternative in {@link SimplifiedSqlParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterOrExpression(SimplifiedSqlParser.OrExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code OrExpression}
	 * labeled alternative in {@link SimplifiedSqlParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitOrExpression(SimplifiedSqlParser.OrExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code Comparison}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void enterComparison(SimplifiedSqlParser.ComparisonContext ctx);
	/**
	 * Exit a parse tree produced by the {@code Comparison}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void exitComparison(SimplifiedSqlParser.ComparisonContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ColumnComparison}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void enterColumnComparison(SimplifiedSqlParser.ColumnComparisonContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ColumnComparison}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void exitColumnComparison(SimplifiedSqlParser.ColumnComparisonContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ComparisonSubquery}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void enterComparisonSubquery(SimplifiedSqlParser.ComparisonSubqueryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ComparisonSubquery}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void exitComparisonSubquery(SimplifiedSqlParser.ComparisonSubqueryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code FunctionComparison}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void enterFunctionComparison(SimplifiedSqlParser.FunctionComparisonContext ctx);
	/**
	 * Exit a parse tree produced by the {@code FunctionComparison}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void exitFunctionComparison(SimplifiedSqlParser.FunctionComparisonContext ctx);
	/**
	 * Enter a parse tree produced by the {@code AggComparison}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void enterAggComparison(SimplifiedSqlParser.AggComparisonContext ctx);
	/**
	 * Exit a parse tree produced by the {@code AggComparison}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void exitAggComparison(SimplifiedSqlParser.AggComparisonContext ctx);
	/**
	 * Enter a parse tree produced by the {@code Between}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void enterBetween(SimplifiedSqlParser.BetweenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code Between}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void exitBetween(SimplifiedSqlParser.BetweenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code In}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void enterIn(SimplifiedSqlParser.InContext ctx);
	/**
	 * Exit a parse tree produced by the {@code In}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void exitIn(SimplifiedSqlParser.InContext ctx);
	/**
	 * Enter a parse tree produced by the {@code InSubquery}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void enterInSubquery(SimplifiedSqlParser.InSubqueryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code InSubquery}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void exitInSubquery(SimplifiedSqlParser.InSubqueryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ExistsSubquery}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void enterExistsSubquery(SimplifiedSqlParser.ExistsSubqueryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ExistsSubquery}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void exitExistsSubquery(SimplifiedSqlParser.ExistsSubqueryContext ctx);
	/**
	 * Enter a parse tree produced by the {@code Like}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void enterLike(SimplifiedSqlParser.LikeContext ctx);
	/**
	 * Exit a parse tree produced by the {@code Like}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void exitLike(SimplifiedSqlParser.LikeContext ctx);
	/**
	 * Enter a parse tree produced by the {@code IsNull}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void enterIsNull(SimplifiedSqlParser.IsNullContext ctx);
	/**
	 * Exit a parse tree produced by the {@code IsNull}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void exitIsNull(SimplifiedSqlParser.IsNullContext ctx);
	/**
	 * Enter a parse tree produced by the {@code IsNotNull}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void enterIsNotNull(SimplifiedSqlParser.IsNotNullContext ctx);
	/**
	 * Exit a parse tree produced by the {@code IsNotNull}
	 * labeled alternative in {@link SimplifiedSqlParser#predicate}.
	 * @param ctx the parse tree
	 */
	void exitIsNotNull(SimplifiedSqlParser.IsNotNullContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#trueFalseExpression}.
	 * @param ctx the parse tree
	 */
	void enterTrueFalseExpression(SimplifiedSqlParser.TrueFalseExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#trueFalseExpression}.
	 * @param ctx the parse tree
	 */
	void exitTrueFalseExpression(SimplifiedSqlParser.TrueFalseExpressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#valueList}.
	 * @param ctx the parse tree
	 */
	void enterValueList(SimplifiedSqlParser.ValueListContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#valueList}.
	 * @param ctx the parse tree
	 */
	void exitValueList(SimplifiedSqlParser.ValueListContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#operator}.
	 * @param ctx the parse tree
	 */
	void enterOperator(SimplifiedSqlParser.OperatorContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#operator}.
	 * @param ctx the parse tree
	 */
	void exitOperator(SimplifiedSqlParser.OperatorContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#value}.
	 * @param ctx the parse tree
	 */
	void enterValue(SimplifiedSqlParser.ValueContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#value}.
	 * @param ctx the parse tree
	 */
	void exitValue(SimplifiedSqlParser.ValueContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#oldNewRef}.
	 * @param ctx the parse tree
	 */
	void enterOldNewRef(SimplifiedSqlParser.OldNewRefContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#oldNewRef}.
	 * @param ctx the parse tree
	 */
	void exitOldNewRef(SimplifiedSqlParser.OldNewRefContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#caseExpr}.
	 * @param ctx the parse tree
	 */
	void enterCaseExpr(SimplifiedSqlParser.CaseExprContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#caseExpr}.
	 * @param ctx the parse tree
	 */
	void exitCaseExpr(SimplifiedSqlParser.CaseExprContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#orderList}.
	 * @param ctx the parse tree
	 */
	void enterOrderList(SimplifiedSqlParser.OrderListContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#orderList}.
	 * @param ctx the parse tree
	 */
	void exitOrderList(SimplifiedSqlParser.OrderListContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#orderItem}.
	 * @param ctx the parse tree
	 */
	void enterOrderItem(SimplifiedSqlParser.OrderItemContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#orderItem}.
	 * @param ctx the parse tree
	 */
	void exitOrderItem(SimplifiedSqlParser.OrderItemContext ctx);
	/**
	 * Enter a parse tree produced by {@link SimplifiedSqlParser#limitClause}.
	 * @param ctx the parse tree
	 */
	void enterLimitClause(SimplifiedSqlParser.LimitClauseContext ctx);
	/**
	 * Exit a parse tree produced by {@link SimplifiedSqlParser#limitClause}.
	 * @param ctx the parse tree
	 */
	void exitLimitClause(SimplifiedSqlParser.LimitClauseContext ctx);
}