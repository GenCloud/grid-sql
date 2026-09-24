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

import index.unit.replication.ReplTestSupport;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Autocommit INSERT must use ephemeral TX unit (not TableStore.upsert bypass). DELETE stays direct store path.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class AutocommitDurableUnitIT {
	private static final String TABLE = "ac_unit";

	@TempDir
	Path tempDir;

	private ReplicationCoordinator coordinator;
	private SqlEngine engine;

	@BeforeEach
	void setUp() throws Exception {
		int port;
		try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
			port = s.getLocalPort();
		}
		final GridConfigurationProperties props =
				ReplTestSupport.props("ac-unit", "ac-unit", "dc-a", port, tempDir, List.of());
		props.getReplication().getOpLog().setFsync(false);
		coordinator = new ReplicationCoordinator(props);
		coordinator.start();
		final long deadline = System.currentTimeMillis() + 3000;
		while (System.currentTimeMillis() < deadline && !coordinator.getOrchidNode().isSynced()) {
			Thread.sleep(10);
		}
		assertTrue(coordinator.getOrchidNode().isSynced());
		engine = SqlBenchHelper.createEngine(1, coordinator);
		SqlBenchHelper.ensureKvTable(engine, TABLE);
	}

	@AfterEach
	void tearDown() {
		if (engine != null && engine.catalog().exists(TABLE)) {
			try {
				engine.catalog().dropTable(TABLE);
			} catch (Exception ignored) {
			}
		}
		if (coordinator != null) {
			coordinator.stop();
		}
	}

	@Test
	void autocommitUpsertIsSingleOpWithoutMarkers() {
		final long before = coordinator.getOrchidNode().getLastCommittedSeq();
		final SqlResult r = engine.execute(
				"INSERT INTO " + TABLE + " (id, v) VALUES (1, 'a')");
		assertEquals(1, r.rowsAffected());
		final long after = coordinator.getOrchidNode().getLastCommittedSeq();
		assertEquals(before + 1L, after);
		final List<ReplicationOp> ops = readTableOpsFrom(before + 1L);
		assertEquals(1, ops.size(), () -> "streamKeys=" + coordinator.getOpLog().streamKeys());
		assertEquals(ReplicationOpType.UPSERT, ops.get(0).type());
		assertFalse(ops.stream().anyMatch(o -> o.type() == ReplicationOpType.TX_BEGIN
				|| o.type() == ReplicationOpType.TX_COMMIT));
	}

	@Test
	void autocommitDeleteIsSingleOpWithoutMarkers() {
		engine.execute("INSERT INTO " + TABLE + " (id, v) VALUES (2, 'b')");
		final long before = coordinator.getOrchidNode().getLastCommittedSeq();
		final SqlResult r = engine.execute("DELETE FROM " + TABLE + " WHERE id = 2");
		assertEquals(1, r.rowsAffected());
		final long after = coordinator.getOrchidNode().getLastCommittedSeq();
		assertEquals(before + 1L, after);
		final List<ReplicationOp> ops = readTableOpsFrom(before + 1L);
		assertEquals(1, ops.size(), () -> "streamKeys=" + coordinator.getOpLog().streamKeys());
		assertEquals(ReplicationOpType.DELETE, ops.get(0).type());
	}

	private List<ReplicationOp> readTableOpsFrom(long fromSeqInclusive) {
		final List<ReplicationOp> found = new ArrayList<>();
		for (String streamKey : coordinator.getOpLog().streamKeys()) {
			if (!streamKey.startsWith(TABLE + "#")) {
				continue;
			}
			final int sep = streamKey.lastIndexOf('#');
			final int shard = Integer.parseInt(streamKey.substring(sep + 1));
			found.addAll(coordinator.getOpLog().readFrom(TABLE, shard, fromSeqInclusive, 32));
		}
		return found;
	}
}