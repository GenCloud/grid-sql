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
package index.benchmarks;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.netty.SqlServer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JDBC {@code ResultSet#deleteRow} latency (autocommit; scalar + composite partial WHERE).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class JdbcDeleteRowBenchmark extends AbstractLatencyBenchmark {
	private static final String TABLE = "bench_del";
	private static final String COMPOSITE_TABLE = "bench_del_comp";

	private SqlEngine engine;
	private SqlServer server;
	private int port;
	private String url;
	private final AtomicInteger nextId = new AtomicInteger(1);
	private final AtomicInteger nextServerId = new AtomicInteger(1);

	@Setup(Level.Trial)
	public void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("jdbc-del-bench")), null, 4);
		engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, v INT)");
		engine.execute("CREATE TABLE " + COMPOSITE_TABLE
				+ " (server_id INT, biset_type VARCHAR, min_value INT, max_value INT,"
				+ " PRIMARY KEY (server_id, biset_type))");
		port = freePort();
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 32);
		server.start();
		TimeUnit.MILLISECONDS.sleep(80);
		Class.forName("org.genfork.grid.jdbc.GridDriver");
		url = "jdbc:grid://u:p@127.0.0.1:" + port + "/public";
	}

	@TearDown(Level.Trial)
	public void tearDown() {
		if (server != null) {
			server.close();
		}
	}

	@Benchmark
	public void deleteRowAutocommit(Blackhole bh) throws Exception {
		final int id = nextId.getAndIncrement();
		try (Connection c = DriverManager.getConnection(url);
				Statement st = c.createStatement()) {
			st.executeUpdate("INSERT INTO " + TABLE + " (id, v) VALUES (" + id + ", 1)");
			try (ResultSet rs = st.executeQuery("SELECT id, v FROM " + TABLE + " WHERE id = " + id)) {
				rs.next();
				rs.deleteRow();
				bh.consume(id);
			}
		}
	}

	/**
	 * DBeaver Save-shaped path: composite PK, {@code deleteRow} emits partial leading-PK WHERE.
	 */
	@Benchmark
	public void deleteRowCompositePartialWhereAutocommit(Blackhole bh) throws Exception {
		final int serverId = nextServerId.getAndIncrement();
		try (Connection c = DriverManager.getConnection(url);
				Statement st = c.createStatement()) {
			st.executeUpdate("INSERT INTO " + COMPOSITE_TABLE
					+ " (server_id, biset_type, min_value, max_value) VALUES ("
					+ serverId + ", 'ITEMS', 1, 2)");
			try (ResultSet rs = st.executeQuery(
					"SELECT server_id, biset_type, min_value, max_value FROM " + COMPOSITE_TABLE
							+ " WHERE server_id = " + serverId)) {
				rs.next();
				rs.deleteRow();
				bh.consume(serverId);
			}
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket ss = new ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
	}
}
