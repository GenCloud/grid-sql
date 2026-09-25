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

import org.genfork.grid.sql.ast.DdlAst.AlterTableSql;
import org.genfork.grid.sql.ast.DmlAst.AnalyzeSql;
import org.genfork.grid.sql.ast.TxAst.BeginSql;
import org.genfork.grid.sql.ast.TxAst.CommitSql;
import org.genfork.grid.sql.ast.DdlAst.CreateFunctionSql;
import org.genfork.grid.sql.ast.DdlAst.CreateTriggerSql;
import org.genfork.grid.sql.ast.DdlAst.CreateIndexSql;
import org.genfork.grid.sql.ast.DdlAst.CreateMaterializedViewSql;
import org.genfork.grid.sql.ast.DdlAst.CreateSchemaSql;
import org.genfork.grid.sql.ast.DdlAst.CreateSequenceSql;
import org.genfork.grid.sql.ast.DdlAst.CreateTableSql;
import org.genfork.grid.sql.ast.AdminAst.AlterUserPasswordSql;
import org.genfork.grid.sql.ast.AdminAst.CreateRoleSql;
import org.genfork.grid.sql.ast.AdminAst.CreateUserSql;
import org.genfork.grid.sql.ast.DdlAst.CreateViewSql;
import org.genfork.grid.sql.ast.TxAst.DeallocateSql;
import org.genfork.grid.sql.ast.DmlAst.DeleteSql;
import org.genfork.grid.sql.ast.DmlAst.TruncateSql;
import org.genfork.grid.sql.ast.DdlAst.DropFunctionSql;
import org.genfork.grid.sql.ast.DdlAst.DropTriggerSql;
import org.genfork.grid.sql.ast.DdlAst.DropIndexSql;
import org.genfork.grid.sql.ast.DdlAst.DropSchemaSql;
import org.genfork.grid.sql.ast.DdlAst.DropSequenceSql;
import org.genfork.grid.sql.ast.DdlAst.DropTableSql;
import org.genfork.grid.sql.ast.AdminAst.DropRoleSql;
import org.genfork.grid.sql.ast.AdminAst.DropUserSql;
import org.genfork.grid.sql.ast.DdlAst.DropViewSql;
import org.genfork.grid.sql.ast.TxAst.ExecuteSql;
import org.genfork.grid.sql.ast.SelectAst.ExplainSql;
import org.genfork.grid.sql.ast.AdminAst.GrantRoleMembershipSql;
import org.genfork.grid.sql.ast.AdminAst.GrantSql;
import org.genfork.grid.sql.ast.AdminAst.GrantToRoleSql;
import org.genfork.grid.sql.ast.DmlAst.InsertSql;
import org.genfork.grid.sql.ast.DmlAst.MergeSql;
import org.genfork.grid.sql.ast.AdminAst.PinSql;
import org.genfork.grid.sql.ast.TxAst.PrepareSql;
import org.genfork.grid.sql.ast.SelectAst.RecursiveCteSql;
import org.genfork.grid.sql.ast.DdlAst.RefreshMaterializedViewSql;
import org.genfork.grid.sql.ast.TxAst.ReleaseSavepointSql;
import org.genfork.grid.sql.ast.AdminAst.RevokeSql;
import org.genfork.grid.sql.ast.TxAst.RollbackSql;
import org.genfork.grid.sql.ast.TxAst.RollbackToSavepointSql;
import org.genfork.grid.sql.ast.TxAst.SavepointSql;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.genfork.grid.sql.ast.DdlAst.SequenceValueSql;
import org.genfork.grid.sql.ast.SelectAst.SetOpSql;
import org.genfork.grid.sql.ast.DdlAst.SetRemoteDirtySql;
import org.genfork.grid.sql.ast.DdlAst.SetSchemaSql;
import org.genfork.grid.sql.ast.Stmt;
import org.genfork.grid.sql.ast.AdminAst.UnpinSql;
import org.genfork.grid.sql.ast.DmlAst.UpdateSql;

/**
 * Maps ANTLR-produced {@link Stmt} to {@link SqlStatementTag} for admission (no string sniff).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlStatementTagger {
	private SqlStatementTagger() {
	}

	public static SqlStatementTag tagOf(Stmt stmt) {
		if (stmt == null) {
			throw new IllegalArgumentException("stmt required");
		}
		return switch (stmt) {
			case BeginSql ignored -> SqlStatementTag.BEGIN;
			case CommitSql ignored -> SqlStatementTag.COMMIT;
			case RollbackSql ignored -> SqlStatementTag.ROLLBACK;
			case SavepointSql ignored -> SqlStatementTag.SAVEPOINT;
			case RollbackToSavepointSql ignored -> SqlStatementTag.ROLLBACK_TO_SAVEPOINT;
			case ReleaseSavepointSql ignored -> SqlStatementTag.RELEASE_SAVEPOINT;
			case PrepareSql ignored -> SqlStatementTag.PREPARE;
			case ExecuteSql ignored -> SqlStatementTag.EXECUTE;
			case DeallocateSql ignored -> SqlStatementTag.DEALLOCATE;
			case CreateUserSql ignored -> SqlStatementTag.CREATE_USER;
			case DropUserSql ignored -> SqlStatementTag.DROP_USER;
			case AlterUserPasswordSql ignored -> SqlStatementTag.ALTER_USER;
			case CreateRoleSql ignored -> SqlStatementTag.CREATE_ROLE;
			case DropRoleSql ignored -> SqlStatementTag.DROP_ROLE;
			case GrantSql ignored -> SqlStatementTag.GRANT;
			case GrantToRoleSql ignored -> SqlStatementTag.GRANT;
			case GrantRoleMembershipSql ignored -> SqlStatementTag.GRANT;
			case RevokeSql ignored -> SqlStatementTag.REVOKE;
			case CreateTableSql ignored -> SqlStatementTag.CREATE_TABLE;
			case CreateSequenceSql ignored -> SqlStatementTag.CREATE_SEQUENCE;
			case DropSequenceSql ignored -> SqlStatementTag.DROP_SEQUENCE;
			case SequenceValueSql ignored -> SqlStatementTag.UPDATE;
			case DropTableSql ignored -> SqlStatementTag.DROP_TABLE;
			case CreateIndexSql ignored -> SqlStatementTag.CREATE_INDEX;
			case DropIndexSql ignored -> SqlStatementTag.DROP_INDEX;
			case CreateSchemaSql ignored -> SqlStatementTag.CREATE_SCHEMA;
			case DropSchemaSql ignored -> SqlStatementTag.DROP_SCHEMA;
			case SetSchemaSql ignored -> SqlStatementTag.SET_SCHEMA;
			case SetRemoteDirtySql ignored -> SqlStatementTag.SET_REMOTE_DIRTY;
			case AlterTableSql ignored -> SqlStatementTag.ALTER_TABLE;
			case CreateViewSql ignored -> SqlStatementTag.CREATE_VIEW;
			case DropViewSql ignored -> SqlStatementTag.DROP_VIEW;
			case CreateMaterializedViewSql ignored -> SqlStatementTag.CREATE_MATERIALIZED_VIEW;
			case RefreshMaterializedViewSql ignored -> SqlStatementTag.REFRESH_MATERIALIZED_VIEW;
			case CreateFunctionSql ignored -> SqlStatementTag.CREATE_FUNCTION;
			case DropFunctionSql ignored -> SqlStatementTag.DROP_FUNCTION;
			case CreateTriggerSql ignored -> SqlStatementTag.CREATE_TRIGGER;
			case DropTriggerSql ignored -> SqlStatementTag.DROP_TRIGGER;
			case InsertSql ignored -> SqlStatementTag.INSERT;
			case MergeSql ignored -> SqlStatementTag.MERGE;
			case AnalyzeSql ignored -> SqlStatementTag.ANALYZE;
			case DeleteSql ignored -> SqlStatementTag.DELETE;
			case TruncateSql ignored -> SqlStatementTag.TRUNCATE;
			case UpdateSql ignored -> SqlStatementTag.UPDATE;
			case SelectSql ignored -> SqlStatementTag.SELECT;
			case SetOpSql ignored -> SqlStatementTag.SELECT;
			case RecursiveCteSql ignored -> SqlStatementTag.SELECT;
			case ExplainSql ignored -> SqlStatementTag.EXPLAIN;
			case PinSql ignored -> SqlStatementTag.PIN;
			case UnpinSql ignored -> SqlStatementTag.UNPIN;
			default -> throw new IllegalArgumentException("unmapped stmt: " + stmt.getClass().getName());
		};
	}
}
