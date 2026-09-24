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
package index.unit.query;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.serial.RowEncoder;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.exec.DistTxSnapshot;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TD-SQL-001: DistTxSnapshot pin is visible to peer key / blob / tombstone suppliers.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class DistTxSnapshotPeerSuppliersIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE snap_t (id INT PRIMARY KEY, value VARCHAR)");
		engine.execute("INSERT INTO snap_t VALUES (1, 'local')");
	}

	@Test
	void peerKeyBlobAndTombstoneSeePinnedEpoch() {
		final AtomicLong keyEpoch = new AtomicLong();
		final AtomicLong blobEpoch = new AtomicLong();
		final AtomicLong tombEpoch = new AtomicLong();
		final byte[] peerKey = SqlWireUtil.toGenericArray(2);
		final TableSchema schema = engine.catalog().getSchema("snap_t");
		final byte[] peerBlob = RowEncoder.encode(schema, new Object[]{2, "peer"});
		final byte[] tombKey = SqlWireUtil.toGenericArray(1);

		engine.setDistributedPeerKeyExecutors(List.of(
				(DistTxSnapshot.SnapshotAwareKeySupplier) (sql, epoch) -> {
					keyEpoch.set(epoch);
					return List.of(peerKey);
				}));
		engine.setDistributedPeerRowBlobFetchers(List.of(
				(DistTxSnapshot.SnapshotAwareBlobFetcher) (table, key, epoch) -> {
					blobEpoch.set(epoch);
					return peerBlob;
				}));
		engine.setRemoteDirtyPeerTombstoneKeyExecutors(List.of(
				(DistTxSnapshot.SnapshotAwareKeySupplier) (table, epoch) -> {
					tombEpoch.set(epoch);
					return List.of(tombKey);
				}));

		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		engine.execute(session, "SET REMOTE_DIRTY TRUE");
		final SqlResult rows = engine.execute(session, "SELECT id, value FROM snap_t WHERE id > 0");
		engine.execute(session, "ROLLBACK");

		assertTrue(keyEpoch.get() > 0L, "peer key supplier must see pinned DistTxSnapshot");
		assertTrue(blobEpoch.get() > 0L, "peer blob fetcher must see pinned DistTxSnapshot");
		assertTrue(tombEpoch.get() > 0L, "peer tombstone supplier must see pinned DistTxSnapshot");
		assertEquals(keyEpoch.get(), blobEpoch.get(), "key and blob share one SELECT horizon");
		assertEquals(keyEpoch.get(), tombEpoch.get(), "tombstone shares one SELECT horizon");

		final List<Integer> ids = new ArrayList<>();
		for (Object[] row : rows.rows()) {
			ids.add(((Number) row[0]).intValue());
		}
		assertTrue(ids.contains(2), "peer key+blob must surface remote-only row");
		assertTrue(!ids.contains(1), "tombstone must hide local committed key 1");
	}
}
