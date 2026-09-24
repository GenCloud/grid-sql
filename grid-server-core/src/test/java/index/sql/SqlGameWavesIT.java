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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration coverage for Game waves G1 RETURNING/types and G2 FOR UPDATE.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public class SqlGameWavesIT {
	private static final long WAIT_TIMEOUT_SECONDS = 5L;
	private static final long BLOCK_CHECK_MILLIS = 100L;

	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
	}

	@Test
	void insertAndUpdateReturningIncludeIdentity() {
		engine.execute("CREATE TABLE events (id SERIAL PRIMARY KEY, payload VARCHAR)");

		final SqlResult inserted = engine.execute(
				"INSERT INTO events (payload) VALUES ('new') RETURNING id, payload");
		assertEquals(1, inserted.rows().getFirst()[0]);
		assertEquals("new", inserted.rows().getFirst()[1]);

		final SqlResult updated = engine.execute(
				"UPDATE events SET payload = 'done' WHERE id = 1 RETURNING *");
		assertEquals(1, updated.rows().getFirst()[0]);
		assertEquals("done", updated.rows().getFirst()[1]);
	}

	@Test
	void byteaAndJsonRoundTripAtSqlResultEdge() {
		engine.execute("CREATE TABLE documents (id INT PRIMARY KEY, raw BYTEA, body JSON)");

		final SqlResult result = engine.execute(
				"INSERT INTO documents VALUES (1, 'wire', '{\"x\":1}') RETURNING raw, body");
		assertArrayEquals("wire".getBytes(StandardCharsets.UTF_8), (byte[]) result.rows().getFirst()[0]);
		assertEquals("{\"x\":1}", result.rows().getFirst()[1]);
	}

	@Test
	void transactionForUpdateSerializesLimitOneConsumers() throws Exception {
		engine.execute("CREATE TABLE outbox (id INT PRIMARY KEY, state VARCHAR)");
		engine.execute("CREATE INDEX outbox_state ON outbox (state)");
		engine.execute("INSERT INTO outbox VALUES (1, 'new')");
		final SqlSession first = engine.newSession();
		final SqlSession second = engine.newSession();
		engine.execute(first, "BEGIN");
		engine.execute(first, "SELECT id FROM outbox WHERE state = 'new' LIMIT 1 FOR UPDATE");

		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch finished = new CountDownLatch(1);
		final AtomicReference<Throwable> failure = new AtomicReference<>();
		final Thread contender = Thread.ofVirtual().start(() -> {
			try {
				engine.execute(second, "BEGIN");
				started.countDown();
				engine.execute(second, "SELECT id FROM outbox WHERE state = 'new' LIMIT 1 FOR UPDATE");
			} catch (Throwable ex) {
				failure.set(ex);
			} finally {
				finished.countDown();
			}
		});

		assertTrue(started.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
		assertFalse(finished.await(BLOCK_CHECK_MILLIS, TimeUnit.MILLISECONDS), () -> "contender completed before unlock: " + failure.get());
		engine.execute(first, "ROLLBACK");
		assertTrue(finished.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
		engine.execute(second, "ROLLBACK");
		contender.join();
		assertNull(failure.get());
	}
}
