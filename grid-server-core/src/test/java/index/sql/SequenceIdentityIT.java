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
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CREATE SEQUENCE / IDENTITY / nextval concurrent allocate.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SequenceIdentityIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("seq-it")), null, 4);
	}

	@Test
	void createSequenceNextvalCurrval() {
		final SqlSession session = engine.newSession();
		engine.execute(session, "CREATE SEQUENCE s1 START WITH 10 INCREMENT BY 2");
		final SqlResult n1 = engine.execute(session, "SELECT nextval('s1')");
		assertEquals(10L, ((Number) n1.rows().getFirst()[0]).longValue());
		final SqlResult c = engine.execute(session, "SELECT currval('s1')");
		assertEquals(10L, ((Number) c.rows().getFirst()[0]).longValue());
		final SqlResult n2 = engine.execute(session, "SELECT nextval('s1')");
		assertEquals(12L, ((Number) n2.rows().getFirst()[0]).longValue());
	}

	@Test
	void identityInsertAllocatesPk() {
		engine.execute("CREATE TABLE t (id SERIAL PRIMARY KEY, name VARCHAR)");
		engine.execute("INSERT INTO t (name) VALUES ('a')");
		engine.execute("INSERT INTO t (name) VALUES ('b')");
		final SqlResult r = engine.execute("SELECT id, name FROM t ORDER BY id");
		assertEquals(2, r.rows().size());
		assertEquals(1, ((Number) r.rows().get(0)[0]).intValue());
		assertEquals(2, ((Number) r.rows().get(1)[0]).intValue());
	}

	@Test
	void concurrentNextvalUnique() throws Exception {
		engine.execute("CREATE SEQUENCE conc START WITH 1 INCREMENT BY 1");
		final int threads = 8;
		final int perThread = 50;
		final Set<Long> values = java.util.Collections.synchronizedSet(new HashSet<>());
		final CountDownLatch start = new CountDownLatch(1);
		final AtomicInteger errors = new AtomicInteger();
		final ExecutorService pool = Executors.newFixedThreadPool(threads);
		for (int t = 0; t < threads; t++) {
			pool.submit(() -> {
				try {
					start.await();
					for (int i = 0; i < perThread; i++) {
						final SqlResult r = engine.execute("SELECT nextval('conc')");
						values.add(Long.valueOf(((Number) r.rows().getFirst()[0]).longValue()));
					}
				} catch (Exception e) {
					errors.incrementAndGet();
				}
			});
		}
		start.countDown();
		pool.shutdown();
		assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
		assertEquals(0, errors.get());
		assertEquals(threads * perThread, values.size());
	}
}