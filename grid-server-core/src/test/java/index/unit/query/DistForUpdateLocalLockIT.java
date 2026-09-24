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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FOR UPDATE v2/v3: dist peer key fan-out does not hard-reject; without peer lock agents
 * locks stay local. Peer lock coordination is covered by {@link DistForUpdatePeerLockIT}.
 * <p>
 * Hot path JMH: {@code index.benchmarks.ForUpdateIndexedSkipLockedBenchmark}.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class DistForUpdateLocalLockIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.execute("CREATE TABLE fu_box (id INT PRIMARY KEY, state VARCHAR)");
		engine.execute("CREATE INDEX fu_box_state ON fu_box (state)");
		engine.execute("INSERT INTO fu_box VALUES (1, 'new'), (2, 'new'), (3, 'done')");
	}

	@Test
	void forUpdateWithPeerFanOutLocksLocalKeysOnly() {
		final AtomicInteger peerCalls = new AtomicInteger();
		engine.setDistributedPeerKeyExecutors(List.of(sql -> {
			peerCalls.incrementAndGet();
			return List.of(SqlWireUtil.toGenericArray(99));
		}));

		final SqlSession first = engine.newSession();
		final SqlSession second = engine.newSession();
		engine.execute(first, "BEGIN");
		engine.execute(second, "BEGIN");
		try {
			final SqlResult locked = engine.execute(first,
					"SELECT id FROM fu_box WHERE state = 'new' FOR UPDATE");
			assertEquals(2, locked.rows().size(), "local indexed keys only (no peer lock / 2PC)");
			assertEquals(0, peerCalls.get(), "FOR UPDATE result path does not fan out peer keys");

			final SqlResult skipped = engine.execute(second,
					"SELECT id FROM fu_box WHERE state = 'new' FOR UPDATE SKIP LOCKED");
			assertEquals(0, skipped.rows().size());
		} finally {
			engine.execute(first, "ROLLBACK");
			engine.execute(second, "ROLLBACK");
		}
	}

	@Test
	void forUpdateDoesNotRejectWhenRemoteDirtyPeersConfigured() {
		engine.setRemoteDirtyPeerKeyExecutors(List.of(sql -> List.of()));
		final SqlSession session = engine.newSession();
		engine.execute(session, "BEGIN");
		final SqlResult rows = engine.execute(session,
				"SELECT id FROM fu_box WHERE state = 'new' LIMIT 1 FOR UPDATE");
		assertEquals(1, rows.rows().size());
		assertTrue(((Number) rows.rows().getFirst()[0]).intValue() >= 1);
		engine.execute(session, "ROLLBACK");
	}
}