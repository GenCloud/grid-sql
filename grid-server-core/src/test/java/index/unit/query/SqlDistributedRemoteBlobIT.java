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

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.serial.RowEncoder;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.4: distributed fan-out fetches row blobs from peers when local committed miss.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class SqlDistributedRemoteBlobIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE items (id INT PRIMARY KEY, name VARCHAR)");
		engine.execute("CREATE TABLE orders (id INT PRIMARY KEY, item_id INT)");
		engine.execute("INSERT INTO orders VALUES (1, 10)");
	}

	@Test
	void keysThenProjectFetchesRemoteBlobOnLocalMiss() {
		final byte[] remoteKey = SqlWireUtil.toGenericArray(10);
		final byte[] remoteBlob = RowEncoder.encode(
				engine.catalog().getSchema("items"),
				new Object[]{10, "remote"});
		final AtomicInteger blobCalls = new AtomicInteger();

		engine.setDistributedPeerKeyExecutors(List.of(sql -> {
			final String upper = sql.toUpperCase(Locale.ROOT);
			if (upper.contains("FROM ITEMS")) {
				return List.of(remoteKey);
			}
			return List.of();
		}));
		engine.setDistributedPeerRowBlobFetchers(List.of((table, key) -> {
			blobCalls.incrementAndGet();
			assertEquals("items", table.toLowerCase(Locale.ROOT));
			assertNotNull(key);
			return remoteBlob;
		}));

		final TableStore items = engine.catalog().getStore("items");
		assertEquals(null, items.getCommittedBytes(remoteKey), "local must miss so peer blob path runs");

		final SqlResult rows = engine.execute("SELECT id, name FROM items");
		assertEquals(1, rows.rows().size());
		assertEquals(10, rows.rows().getFirst()[0]);
		assertEquals("remote", rows.rows().getFirst()[1]);
		assertTrue(blobCalls.get() >= 1, "peer blob fetcher must run on local miss");
	}

	@Test
	void joinBuildFanInUsesRemoteBlobOnLocalMiss() {
		final byte[] remoteKey = SqlWireUtil.toGenericArray(10);
		final byte[] remoteBlob = RowEncoder.encode(
				engine.catalog().getSchema("items"),
				new Object[]{10, "peer-item"});
		final AtomicInteger blobCalls = new AtomicInteger();

		engine.setDistributedPeerKeyExecutors(List.of(sql -> {
			final String upper = sql.toUpperCase(Locale.ROOT);
			if (upper.contains("FROM ITEMS")) {
				return List.of(remoteKey);
			}
			return List.of();
		}));
		engine.setDistributedPeerRowBlobFetchers(List.of((table, key) -> {
			if ("items".equalsIgnoreCase(table)) {
				blobCalls.incrementAndGet();
				return remoteBlob;
			}
			return null;
		}));

		final SqlResult rows = engine.execute(
				"SELECT * FROM orders JOIN items ON item_id = id");
		assertEquals(1, rows.rows().size());
		assertTrue(blobCalls.get() >= 1);
	}

	@Test
	void crossDomainAliasResolvesJoinPartner() {
		engine.registerCrossDomainAlias("i", "items");
		engine.execute("INSERT INTO items VALUES (10, 'local')");
		final SqlResult rows = engine.execute(
				"SELECT * FROM orders JOIN i ON item_id = id");
		assertEquals(1, rows.rows().size());
		assertTrue(engine.crossDomain().isAlias("i"));
	}
}
