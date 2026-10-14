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
package index.sql;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlExecErrorContextUtil;
import org.genfork.grid.sql.SqlReplicaAdmission;
import org.genfork.grid.sql.netty.SqlWireErrorCodes;
import org.genfork.grid.sql.tx.LockWaitTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SqlEngine enriches EXEC failures with SQL/binds; wireErrorCode keeps typed codes.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class SqlExecExceptionContextIT {
	private static final String BAD_TABLE_SQL = "SELECT id FROM no_such_table_xyz WHERE id = ?";
	private static final String SQL_MARKER = "no_such_table_xyz";
	private static final String BINDS_MARKER = "binds=";

	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(false);
		props.getReplication().setEnabled(false);
		engine = new SqlEngine(
				new TableCatalog(),
				new ReplicationCoordinator(props),
				props.getSql().getDefaultShards());
	}

	@Test
	void executeFailure_enrichesWithSqlAndBinds() {
		final Object[] binds = new Object[]{42};
		final RuntimeException ex = assertThrows(RuntimeException.class,
				() -> engine.execute(engine.newSession(), BAD_TABLE_SQL, binds));
		assertTrue(ex.getMessage().contains(SQL_MARKER), ex.getMessage());
		assertTrue(ex.getMessage().contains(BINDS_MARKER), ex.getMessage());
		assertTrue(ex.getMessage().contains("42"), ex.getMessage());
		assertTrue(ex.getMessage().contains(SqlExecErrorContextUtil.CTX_SEP), ex.getMessage());
	}

	@Test
	void wireErrorCode_keepsLockWaitType() {
		final LockWaitTimeoutException cause = new LockWaitTimeoutException("lock wait");
		final RuntimeException enriched = SqlExecErrorContextUtil.enrich(cause, "SELECT 1", null);
		assertInstanceOf(LockWaitTimeoutException.class, enriched);
		assertTrue(enriched.getMessage().contains(SqlExecErrorContextUtil.CTX_SEP), enriched.getMessage());
		assertEquals(
				SqlWireErrorCodes.LOCK_WAIT_TIMEOUT,
				SqlReplicaAdmission.wireErrorCode(enriched));
	}

	@Test
	void wireErrorCode_keepsOrchidStaleType() {
		final OrchidNotSyncedException cause =
				new OrchidNotSyncedException("stale replica read (apply lag exceeded)");
		final RuntimeException enriched = SqlExecErrorContextUtil.enrich(cause, "SELECT 1", new Object[]{"x"});
		assertEquals(
				SqlWireErrorCodes.REPLICA_READ_STALE,
				SqlReplicaAdmission.wireErrorCode(enriched));
	}
}
