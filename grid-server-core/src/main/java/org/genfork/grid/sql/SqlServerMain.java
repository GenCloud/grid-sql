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
package org.genfork.grid.sql;

import java.nio.file.Path;

/**
 * Standalone SQL TCP server for IDE / local tooling (no Spring Boot).
 * <p>
 * Run configuration (IDE): main class {@code org.genfork.grid.sql.SqlServerMain},
 * module {@code grid-server-core}, VM options {@code --enable-preview}.
 * Then connect DBeaver with {@code jdbc:grid://grid:grid@127.0.0.1:15432/public}.
 *
 * <pre>
 * java --enable-preview -cp ... org.genfork.grid.sql.SqlServerMain
 *   [--host 0.0.0.0] [--port 15432] [--data-dir ./data]
 *   [--shards 4] [--user grid] [--password grid]
 * </pre>
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlServerMain {
	private SqlServerMain() {
	}

	public static void main(String[] args) throws Exception {
		String host = SqlServerRuntime.DEFAULT_BIND_HOST;
		int port = SqlServerRuntime.DEFAULT_PORT;
		Path dataDir = Path.of("./data/sql-server");
		int shards = SqlServerRuntime.DEFAULT_SHARDS;
		String user = SqlServerRuntime.DEFAULT_AUTH_USER;
		String password = SqlServerRuntime.DEFAULT_AUTH_PASSWORD;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--host", "-h" -> host = args[++i];
				case "--port", "-p" -> port = Integer.parseInt(args[++i]);
				case "--data-dir" -> dataDir = Path.of(args[++i]);
				case "--shards" -> shards = Integer.parseInt(args[++i]);
				case "--user", "-u" -> user = args[++i];
				case "--password", "-P" -> password = args[++i];
				default -> {
				}
			}
		}
		final SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(shards)
				.listen(true)
				.bind(host, port)
				.auth(user, password)
				.build()
				.start();
		Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "sql-server-shutdown"));
		System.out.println("grid SQL server listening on " + host + ":" + port);
		System.out.println("JDBC URL: jdbc:grid://" + user + ":" + password + "@127.0.0.1:" + port + "/public");
		System.out.println("SPI URL:  grid://" + user + ":" + password + "@127.0.0.1:" + port + "/public");
		Thread.currentThread().join();
	}
}
