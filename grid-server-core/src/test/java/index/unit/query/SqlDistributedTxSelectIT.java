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
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.exec.SqlExplainKinds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.2: read-only distributed SELECT/JOIN fan-out while session is in TX
 * (committed peers; local dirty overlay).
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class SqlDistributedTxSelectIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE orders (id INT PRIMARY KEY, cid INT)");
		engine.execute("CREATE TABLE customers (id INT PRIMARY KEY, name VARCHAR)");
		engine.execute("INSERT INTO customers VALUES (1, 'a')");
		engine.execute("INSERT INTO customers VALUES (2, 'b')");
		engine.execute("INSERT INTO orders VALUES (10, 1)");
		engine.execute("INSERT INTO orders VALUES (20, 2)");
	}

	@Test
	void selectFansOutWhileInTransaction() {
		final AtomicInteger peerCalls = new AtomicInteger();
		engine.setDistributedPeerKeyExecutors(List.of(sql -> {
			peerCalls.incrementAndGet();
			return List.of(SqlWireUtil.toGenericArray(3));
		}));
		engine.execute("INSERT INTO orders VALUES (30, 1)");

		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		final SqlResult rows = engine.execute(session, "SELECT id FROM orders WHERE id > 0");
		assertTrue(peerCalls.get() >= 1, "in-TX SELECT must fan out to peers for committed keys");
		assertTrue(rows.rows().size() >= 3);
		engine.execute(session, "ROLLBACK");
	}

	@Test
	void joinDistFanoutAllowedInTxAndExplainsTxMode() {
		final AtomicInteger peerCalls = new AtomicInteger();
		engine.setDistributedPeerKeyExecutors(List.of(sql -> {
			peerCalls.incrementAndGet();
			final String upper = sql.toUpperCase(Locale.ROOT);
			assertTrue(upper.contains("FROM ORDERS") || upper.contains("FROM CUSTOMERS"));
			if (upper.contains("FROM ORDERS")) {
				return List.of(SqlWireUtil.toGenericArray(10), SqlWireUtil.toGenericArray(20));
			}
			return List.of(SqlWireUtil.toGenericArray(1), SqlWireUtil.toGenericArray(2));
		}));

		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");

		final SqlResult explain = engine.execute(
				session, "EXPLAIN SELECT * FROM orders JOIN customers ON cid = id");
		assertEquals(SqlExplainKinds.JOIN_DIST_FANOUT, explain.rows().getFirst()[0]);
		assertTrue(
				String.valueOf(explain.rows().getFirst()[2]).contains(SqlExplainKinds.TX_MODE_COMMITTED_FANOUT),
				"EXPLAIN must mark tx-mode for in-TX dist JOIN");

		final SqlResult rows = engine.execute(
				session, "SELECT * FROM orders JOIN customers ON cid = id");
		assertEquals(2, rows.rows().size());
		assertTrue(peerCalls.get() >= 1, "in-TX JOIN must call peer suppliers");

		engine.execute(session, "ROLLBACK");
	}

	@Test
	void dirtyUpsertStaysLocalDuringDistSelect() {
		final AtomicInteger peerCalls = new AtomicInteger();
		engine.setDistributedPeerKeyExecutors(List.of(sql -> {
			peerCalls.incrementAndGet();
			return List.of();
		}));

		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		engine.execute(session, "INSERT INTO orders VALUES (99, 1)");
		final SqlResult rows = engine.execute(session, "SELECT id FROM orders WHERE id = 99");
		assertEquals(1, rows.rows().size());
		assertEquals(99, ((Number) rows.rows().getFirst()[0]).intValue());
		assertTrue(peerCalls.get() >= 0);
		engine.execute(session, "ROLLBACK");
	}
}