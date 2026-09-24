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

import java.nio.file.Files;
import java.util.List;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.metrics.DistributedQueryMetrics;
import org.genfork.grid.serial.RowEncoder;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the default-off and explicit opt-in remote dirty fan-out model.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class RemoteDirtyFanOutIT {
	private SqlEngine engine;
	private SqlSession session;

	@BeforeEach
	void setUp() throws Exception {
		final TableCatalog catalog = new TableCatalog(Files.createTempDirectory("remote-dirty"));
		engine = new SqlEngine(catalog, null, 4);
		engine.execute("CREATE TABLE rd_t (id INT PRIMARY KEY, value VARCHAR)");
		final TableSchema schema = catalog.getSchema("rd_t");
		final TableStore store = catalog.getStore("rd_t");
		final byte[] dirtyKey = store.keyBytesForPk(2);
		final byte[] dirtyBlob = RowEncoder.encode(schema, new Object[]{2, "peer-dirty"});
		engine.setRemoteDirtyPeerKeyExecutors(List.of(sql -> List.of(dirtyKey)));
		engine.setRemoteDirtyPeerRowBlobFetchers(List.of((table, key) -> dirtyBlob));
		session = engine.newSession();
		engine.execute(session, "BEGIN");
	}

	@Test
	void remoteDirtyIsInvisibleByDefaultAndVisibleOnlyWhileEnabled() {
		final long fanInCallsBefore = DistributedQueryMetrics.fanInCalls();
		final long fanInRowsBefore = DistributedQueryMetrics.fanInRows();
		assertFalse(session.remoteDirtyEnabled());
		assertEquals(0, selectDirty().rows().size());

		engine.execute(session, "SET REMOTE_DIRTY TRUE");
		assertTrue(session.remoteDirtyEnabled());
		final SqlResult enabled = selectDirty();
		assertEquals(1, enabled.rows().size());
		assertEquals(2, enabled.rows().getFirst()[0]);
		assertEquals("peer-dirty", enabled.rows().getFirst()[1]);
		assertTrue(DistributedQueryMetrics.fanInCalls() > fanInCallsBefore);
		assertTrue(DistributedQueryMetrics.fanInRows() > fanInRowsBefore);

		engine.execute(session, "SET REMOTE_DIRTY FALSE");
		assertFalse(session.remoteDirtyEnabled());
		assertEquals(0, selectDirty().rows().size());
	}

	@Test
	void remoteDirtyTombstoneHidesCommittedKeyWhenEnabled() throws Exception {
		engine.execute(session, "INSERT INTO rd_t (id, value) VALUES (9, 'local-commit')");
		engine.execute(session, "COMMIT");
		engine.execute(session, "BEGIN");
		final TableStore store = engine.catalog().getStore("rd_t");
		final byte[] tombKey = store.keyBytesForPk(9);
		engine.setRemoteDirtyPeerTombstoneKeyExecutors(List.of(sql -> List.of(tombKey)));

		assertEquals(1, engine.execute(session, "SELECT id FROM rd_t WHERE id = 9").rows().size());

		engine.execute(session, "SET REMOTE_DIRTY TRUE");
		assertEquals(0, engine.execute(session, "SELECT id FROM rd_t WHERE id = 9").rows().size());

		engine.execute(session, "SET REMOTE_DIRTY FALSE");
		assertEquals(1, engine.execute(session, "SELECT id FROM rd_t WHERE id = 9").rows().size());
	}

	private SqlResult selectDirty() {
		return engine.execute(session, "SELECT id, value FROM rd_t WHERE value = 'peer-dirty'");
	}
}
