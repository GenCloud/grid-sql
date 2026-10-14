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

import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.sql.SqlServerRuntime;
import org.genfork.grid.sql.client.Connection;
import org.genfork.grid.sql.client.ConnectionFactory;
import org.genfork.grid.sql.client.TxContext;
import org.genfork.grid.sql.netty.SqlServer;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boot-equivalent wiring: {@code grid.sql-server.max-tx-contexts} reaches the TCP listener.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class SqlServerMaxTxContextsConfigIT {
	private static final int CONFIGURED_CAP = 3;

	@Test
	void runtimeBuilderAppliesMaxTxContextsToListener() throws Exception {
		final int port = freePort();
		try (SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(Files.createTempDirectory("sql-max-tx-cfg"))
				.shards(4)
				.durability(false)
				.listen(true)
				.bind("127.0.0.1", port)
				.auth("u", "p")
				.maxTxContexts(CONFIGURED_CAP)
				.build()
				.start()) {
			assertEquals(CONFIGURED_CAP, runtime.tcpServer().maxTxContexts());

			final ConnectionFactory factory = ConnectionFactory.fromUrl(
					"grid://u:p@127.0.0.1:" + port + "/public?minConnections=1&maxConnections=1&maxTxContexts=32");
			try {
				final Connection conn = factory.obtain().block();
				assertNotNull(conn);
				final List<TxContext> held = new ArrayList<>();
				try {
					for (int i = 0; i < CONFIGURED_CAP; i++) {
						held.add(conn.begin().block());
					}
					final IllegalStateException ex = assertThrows(
							IllegalStateException.class,
							() -> conn.begin().block());
					assertTrue(ex.getMessage().contains("maxTxContexts=" + CONFIGURED_CAP));
				} finally {
					for (TxContext tx : held) {
						if (tx != null) {
							tx.rollback().block();
						}
					}
					conn.close().block();
				}
			} finally {
				factory.dispose();
			}
		}
	}

	@Test
	void sqlServerPropsDefaultMatchesListenerDefault() {
		assertEquals(
				SqlServer.DEFAULT_MAX_TX_CONTEXTS,
				new GridConfigurationProperties().getSqlServer().getMaxTxContexts());
	}

	private static int freePort() throws Exception {
		try (ServerSocket ss = new ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
	}
}
