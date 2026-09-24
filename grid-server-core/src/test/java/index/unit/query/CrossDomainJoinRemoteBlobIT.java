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
import org.genfork.grid.utils.SerialUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.4: cross-domain JOIN alias registry + remote row blob fetch on fan-out.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class CrossDomainJoinRemoteBlobIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE cd_left (id INT PRIMARY KEY, k INT)");
		engine.execute("CREATE TABLE cd_right (id INT PRIMARY KEY, k INT)");
		engine.execute("INSERT INTO cd_left VALUES (1, 7)");
		engine.execute("INSERT INTO cd_right VALUES (10, 7)");
	}

	@Test
	void crossDomainAliasResolvesJoinSide() {
		engine.registerCrossDomainAlias("r_alias", "cd_right");
		final SqlResult r = engine.execute(
				"SELECT cd_left.id, r_alias.id FROM cd_left JOIN r_alias ON k = k");
		assertEquals(1, r.rows().size());
		assertEquals(1, r.rows().getFirst()[0]);
		assertEquals(10, r.rows().getFirst()[1]);
	}

	@Test
	void fanInFetchesRemoteRowBlobWhenLocalMiss() {
		final TableStore right = engine.catalog().getStore("cd_right");
		final byte[] remoteKey = SqlWireUtil.toGenericArray(99);
		final Object[] remoteRow = new Object[]{99, 7};
		final byte[] remoteBlob = RowEncoder.encode(right.schema(), remoteRow);

		final AtomicInteger keyCalls = new AtomicInteger();
		final AtomicInteger blobCalls = new AtomicInteger();
		engine.setDistributedPeerKeyExecutors(List.of(sql -> {
			keyCalls.incrementAndGet();
			return List.of(remoteKey);
		}));
		engine.setDistributedPeerRowBlobFetchers(List.of((table, key) -> {
			blobCalls.incrementAndGet();
			assertEquals("cd_right", table);
			final Object pk = SerialUtil.readPrimitives(key, 0, Integer.class);
			assertEquals(99, ((Number) pk).intValue());
			return remoteBlob;
		}));

		final SqlResult r = engine.execute(
				"SELECT cd_left.id, cd_right.id FROM cd_left JOIN cd_right ON k = k");
		assertTrue(keyCalls.get() >= 1, "peer key fan-out must run");
		assertTrue(blobCalls.get() >= 1, "peer blob fetch must run for remote-only key");
		assertTrue(r.rows().size() >= 1);
		boolean sawRemote = false;
		for (Object[] row : r.rows()) {
			final Object rightId = row[1];
			if (rightId instanceof Number n && n.intValue() == 99) {
				sawRemote = true;
				break;
			}
		}
		assertTrue(sawRemote, "JOIN must include peer-only row blob");
	}
}
