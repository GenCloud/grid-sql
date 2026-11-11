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
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.client.RemoteConnection;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.client.TxContext;
import org.genfork.grid.sql.netty.SqlServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Abrupt TCP death mid-TX must release record locks (sleep/wake / client kill without
 * {@code SESSION_CLOSE}).
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class SqlDisconnectMidTxLockIT {
	private static final String TABLE = "disc_lock_t";
	private static final long LOCK_WAIT_MS = 2_000L;
	private static final Duration SECOND_UPDATE_BUDGET = Duration.ofMillis(1_500L);
	private static final long ABANDON_SETTLE_MS = 250L;
	private static final long SERVER_READY_MS = 120L;

	private SqlEngine engine;
	private SqlServer server;
	private RemoteConnectionFactory remoteFactory;
	private int port;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("disc-mid-tx")), null, 4);
		engine.lockManager().setLockWaitTimeoutMs(LOCK_WAIT_MS);
		port = 25710 + (int) (Math.abs(System.nanoTime()) % 400);
	}

	@AfterEach
	void tearDown() {
		if (remoteFactory != null) {
			remoteFactory.dispose();
			remoteFactory = null;
		}
		if (server != null) {
			server.close();
			server = null;
		}
	}

	@Test
	void abruptChannelCloseReleasesRecordLockForNextClient() throws Exception {
		engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO " + TABLE + " VALUES (1, 0)");
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 8);
		server.start();
		TimeUnit.MILLISECONDS.sleep(SERVER_READY_MS);

		remoteFactory = new RemoteConnectionFactory("127.0.0.1", port, "u", "p", 8, 2, "public", false);
		final RemoteConnection holder = (RemoteConnection) remoteFactory.obtain().block(Duration.ofSeconds(5L));
		final TxContext tx = holder.begin().block(Duration.ofSeconds(5L));
		tx.createStatement("UPDATE " + TABLE + " SET v = 1 WHERE id = 1")
				.execute()
				.blockLast(Duration.ofSeconds(5L));

		// Abrupt OS-style death: no commit / rollback / TxContext.close / SESSION_CLOSE.
		holder.channel().close().syncUninterruptibly();
		TimeUnit.MILLISECONDS.sleep(ABANDON_SETTLE_MS);

		assertTimeoutPreemptively(SECOND_UPDATE_BUDGET, () -> {
			final RemoteConnection next = (RemoteConnection) remoteFactory.obtain().block(Duration.ofSeconds(5L));
			assertTrue(next.isOpen());
			final TxContext tx2 = next.begin().block(Duration.ofSeconds(5L));
			tx2.createStatement("UPDATE " + TABLE + " SET v = 2 WHERE id = 1")
					.execute()
					.blockLast(Duration.ofSeconds(5L));
			tx2.commit().block(Duration.ofSeconds(5L));
			next.close().block(Duration.ofSeconds(5L));
		});

		assertEquals(2, ((Number) engine.execute("SELECT v FROM " + TABLE + " WHERE id = 1")
				.rows().getFirst()[0]).intValue());
	}
}
