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

import org.genfork.grid.metrics.SqlTxMetrics;
import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.ReplicaAccessGate;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.ast.Stmt;
import org.genfork.grid.sql.client.SessionRole;
import org.genfork.grid.sql.netty.SqlWireErrorCodes;
import org.genfork.grid.sql.tx.LockWaitCancelledException;
import org.genfork.grid.sql.tx.LockWaitTimeoutException;

/**
 * Post-ANTLR admission for PRIMARY vs READ_REPLICA sessions (shared gate helpers).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlReplicaAdmission {
	public static final String READ_REPLICA_DML_DENIED_MSG =
			"READ_REPLICA session allows SELECT/EXPLAIN only";
	private static final String MSG_REPLICA_READS_DISABLED = "replica reads disabled";
	private static final String MSG_STALE_REPLICA = "stale replica";
	private static final String MSG_APPLY_LAG = "apply lag";

	private SqlReplicaAdmission() {
	}

	/**
	 * After parse: READ_REPLICA rejects non-read tags; then replica vs linearizable gate.
	 * PRIMARY leaves mutation admission to existing {@link ReplicaAccessGate#ensureWrite} sites.
	 */
	public static void afterParse(
			SqlSession session,
			Stmt stmt,
			ReplicationCoordinator replication) {
		final SessionRole role = session == null ? SessionRole.PRIMARY : session.sessionRole();
		final SqlStatementTag tag = SqlStatementTagger.tagOf(stmt);
		if (role.isReadReplica()) {
			if (!ReplicaReadStatementTags.allows(tag)) {
				SqlTxMetrics.recordReplicaReadDenied();
				throw new SqlReplicaDmlDeniedException(READ_REPLICA_DML_DENIED_MSG);
			}
			if (session != null && session.inTransaction()) {
				SqlTxMetrics.recordReplicaReadDenied();
				throw new SqlReplicaDmlDeniedException(READ_REPLICA_DML_DENIED_MSG);
			}
			try {
				ReplicaAccessGate.ensureReplicaRead(replication);
			} catch (OrchidNotSyncedException ex) {
				SqlTxMetrics.recordReplicaReadDenied();
				throw ex;
			}
			SqlTxMetrics.recordReplicaRead();
			return;
		}
		if (ReplicaReadStatementTags.allows(tag)) {
			ReplicaAccessGate.ensureLinearizableRead(replication);
		}
	}

	/**
	 * Map admission failures to wire error codes (named constants).
	 */
	public static int wireErrorCode(Throwable ex) {
		if (ex instanceof SqlReplicaDmlDeniedException) {
			return SqlWireErrorCodes.READ_REPLICA_DML_DENIED;
		}
		if (ex instanceof LockWaitTimeoutException) {
			return SqlWireErrorCodes.LOCK_WAIT_TIMEOUT;
		}
		if (ex instanceof LockWaitCancelledException) {
			return SqlWireErrorCodes.LOCK_WAIT_CANCELLED;
		}
		if (ex instanceof OrchidNotSyncedException) {
			final String msg = ex.getMessage() == null ? "" : ex.getMessage();
			if (msg.contains(MSG_REPLICA_READS_DISABLED)) {
				return SqlWireErrorCodes.REPLICA_READ_DISABLED;
			}
			if (msg.contains(MSG_STALE_REPLICA) || msg.contains(MSG_APPLY_LAG)) {
				return SqlWireErrorCodes.REPLICA_READ_STALE;
			}
		}
		return SqlWireErrorCodes.EXEC_FAILED;
	}
}
