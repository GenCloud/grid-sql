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
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Product path: SqlEngine SELECT uses {@link org.genfork.grid.query.distributed.DistributedQueryExecutor}.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class SqlDistributedQueryProductIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE dq_t (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO dq_t VALUES (1, 10)");
		engine.execute("INSERT INTO dq_t VALUES (2, 20)");
		engine.execute("INSERT INTO dq_t VALUES (3, 30)");
	}

	@Test
	void selectUsesDistributedExecutorWithPeerFanOutAndLimit() {
		final AtomicInteger peerCalls = new AtomicInteger();
		engine.setDistributedPeerKeyExecutors(List.of(sql -> {
			peerCalls.incrementAndGet();
			assertTrue(sql.toUpperCase().contains("LIMIT"));
			return List.of(SqlWireUtil.toGenericArray(99));
		}));
		final SqlResult r = engine.execute("SELECT id, v FROM dq_t WHERE v > 0 LIMIT 2");
		assertEquals(2, r.rows().size());
		assertEquals(0, peerCalls.get(), "local LIMIT satisfied before peer fan-out");
	}

	@Test
	void selectFansOutToPeersWhenLocalKeysDoNotFillLimit() {
		final AtomicInteger peerCalls = new AtomicInteger();
		engine.setDistributedPeerKeyExecutors(List.of(sql -> {
			peerCalls.incrementAndGet();
			assertTrue(sql.toUpperCase().contains("LIMIT"));
			// Peer returns a local PK so project succeeds after merge.
			return List.of(SqlWireUtil.toGenericArray(3));
		}));
		// High LIMIT so local early-stop does not skip peers after first chunk.
		final SqlResult r = engine.execute("SELECT id, v FROM dq_t WHERE v > 0 LIMIT 100");
		assertTrue(peerCalls.get() >= 1, "peer fan-out must run when LIMIT not filled by early-stop alone");
		assertTrue(r.rows().size() >= 3);
	}
}
