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
import org.genfork.grid.sql.exec.SqlExplainKinds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.3: distributed JOIN build fan-in + local JOIN_PK/HASH; EXPLAIN {@link SqlExplainKinds#JOIN_DIST_FANOUT}.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class SqlDistributedJoinIT {
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
	void joinPkProbeFansInBuildKeysAndExplainsDistFanout() {
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

		final SqlResult explain = engine.execute(
				"EXPLAIN SELECT * FROM orders JOIN customers ON cid = id");
		assertEquals(SqlExplainKinds.JOIN_DIST_FANOUT, explain.rows().getFirst()[0]);
		assertTrue(String.valueOf(explain.rows().getFirst()[2]).contains("JOIN_PK"));

		final SqlResult rows = engine.execute(
				"SELECT * FROM orders JOIN customers ON cid = id");
		assertEquals(2, rows.rows().size());
		assertTrue(peerCalls.get() >= 1, "build-side fan-in must call peer suppliers");
	}

	@Test
	void joinHashFansInBuildSideKeys() {
		engine.execute("CREATE TABLE left_t (id INT PRIMARY KEY, k INT)");
		engine.execute("CREATE TABLE right_t (id INT PRIMARY KEY, k INT)");
		engine.execute("INSERT INTO left_t VALUES (1, 7)");
		engine.execute("INSERT INTO right_t VALUES (2, 7)");

		final AtomicInteger peerCalls = new AtomicInteger();
		engine.setDistributedPeerKeyExecutors(List.of(sql -> {
			peerCalls.incrementAndGet();
			final String upper = sql.toUpperCase(Locale.ROOT);
			if (upper.contains("FROM RIGHT_T")) {
				return List.of(SqlWireUtil.toGenericArray(2));
			}
			return List.of();
		}));

		final SqlResult explain = engine.execute(
				"EXPLAIN SELECT * FROM left_t JOIN right_t ON k = k");
		assertEquals(SqlExplainKinds.JOIN_DIST_FANOUT, explain.rows().getFirst()[0]);
		assertTrue(String.valueOf(explain.rows().getFirst()[2]).contains("JOIN_HASH"));

		final SqlResult rows = engine.execute("SELECT * FROM left_t JOIN right_t ON k = k");
		assertEquals(1, rows.rows().size());
		assertTrue(peerCalls.get() >= 1);
	}
}