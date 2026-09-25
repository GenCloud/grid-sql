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
package org.genfork.grid.sql.ast;

/**
 * Sealed root for typed SQL statement AST produced by {@link org.genfork.grid.sql.SqlStatementParser}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public sealed interface Stmt permits
		SelectAst.SelectSql,
		SelectAst.SetOpSql,
		SelectAst.RecursiveCteSql,
		SelectAst.ExplainSql,
		DmlAst.InsertSql,
		DmlAst.MergeSql,
		DmlAst.AnalyzeSql,
		DmlAst.DeleteSql,
		DmlAst.TruncateSql,
		DmlAst.UpdateSql,
		DdlAst.CreateTableSql,
		DdlAst.DropTableSql,
		DdlAst.CreateIndexSql,
		DdlAst.DropIndexSql,
		DdlAst.CreateSchemaSql,
		DdlAst.DropSchemaSql,
		DdlAst.SetSchemaSql,
		DdlAst.SetRemoteDirtySql,
		DdlAst.AlterTableSql,
		DdlAst.CreateViewSql,
		DdlAst.DropViewSql,
		DdlAst.CreateMaterializedViewSql,
		DdlAst.RefreshMaterializedViewSql,
		DdlAst.CreateFunctionSql,
		DdlAst.DropFunctionSql,
		DdlAst.CreateTriggerSql,
		DdlAst.DropTriggerSql,
		DdlAst.CreateSequenceSql,
		DdlAst.DropSequenceSql,
		DdlAst.SequenceValueSql,
		TxAst.BeginSql,
		TxAst.CommitSql,
		TxAst.RollbackSql,
		TxAst.SavepointSql,
		TxAst.RollbackToSavepointSql,
		TxAst.ReleaseSavepointSql,
		TxAst.PrepareSql,
		TxAst.ExecuteSql,
		TxAst.DeallocateSql,
		AdminAst.PinSql,
		AdminAst.UnpinSql,
		AdminAst.CreateUserSql,
		AdminAst.DropUserSql,
		AdminAst.AlterUserPasswordSql,
		AdminAst.CreateRoleSql,
		AdminAst.DropRoleSql,
		AdminAst.GrantSql,
		AdminAst.GrantToRoleSql,
		AdminAst.GrantRoleMembershipSql,
		AdminAst.RevokeSql {
}