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

import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import index.sql.SqlBenchHelper;

import org.genfork.grid.jdbc.GridDriver;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.client.Connection;
import org.genfork.grid.sql.client.RemoteConnection;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.client.sync.SyncAwait;
import org.genfork.grid.sql.client.sync.SyncConnection;
import org.genfork.grid.sql.netty.SqlServer;

/**
 * Dual-exchange stamp: reactive SPI vs Sync/JDBC autocommit DML over TCP loopback.
 * <p>
 * Measurement path for {@code ReactiveExecExchange} / {@code SyncExecExchange} (critical-engineering §8).
 * Not a living capacity floor — informational compare only.
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class SqlClientDualExchangeBenchmark extends AbstractLatencyBenchmark {

	private static final int PORT = 25911;
	private static final Duration OP_TIMEOUT = Duration.ofSeconds(10);

	private SqlEngine engine;
	private SqlServer server;
	private RemoteConnectionFactory factory;
	private Connection reactive;
	private SyncConnection sync;
	private java.sql.Connection jdbc;
	private Statement jdbcStatement;
	private final AtomicInteger nextId = new AtomicInteger(1_000_000);

	@Setup
	public void setup() throws Exception {
		engine = SqlBenchHelper.createEngine(4);
		SqlBenchHelper.ensureKvTable(engine, "dual_ex_t");
		server = new SqlServer("127.0.0.1", PORT, engine, "u", "p", 32);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		factory = new RemoteConnectionFactory("127.0.0.1", PORT, "u", "p", 32, 4, "public", false);
		reactive = factory.obtain().block(OP_TIMEOUT);
		final Connection syncRemote = SyncAwait.await(factory.obtainStage(), OP_TIMEOUT);
		sync = new SyncConnection((RemoteConnection) syncRemote, factory, OP_TIMEOUT, null);
		Class.forName(GridDriver.class.getName());
		jdbc = DriverManager.getConnection("jdbc:grid://u:p@127.0.0.1:" + PORT + "/public");
		jdbcStatement = jdbc.createStatement();
		reactive.createStatement("INSERT INTO dual_ex_t (id, v) VALUES (1, 'warmup')")
				.executeUpdate()
				.block(OP_TIMEOUT);
		sync.executeUpdate("INSERT INTO dual_ex_t (id, v) VALUES (2, 'warmup')");
		jdbcStatement.executeUpdate("INSERT INTO dual_ex_t (id, v) VALUES (3, 'warmup')");
	}

	@TearDown
	public void tearDown() throws Exception {
		if (jdbcStatement != null) {
			jdbcStatement.close();
		}
		if (jdbc != null) {
			jdbc.close();
		}
		if (sync != null) {
			sync.close();
		}
		if (reactive != null) {
			reactive.close().block(OP_TIMEOUT);
		}
		if (factory != null) {
			factory.dispose();
		}
		if (server != null) {
			server.close();
		}
	}

	@Benchmark
	public void reactiveAutocommitInsert(Blackhole bh) {
		final int id = nextId.getAndIncrement();
		final Long n = reactive.executeUpdate(
				"INSERT INTO dual_ex_t (id, v) VALUES (" + id + ", 'r')")
				.block(OP_TIMEOUT);
		bh.consume(n);
	}

	@Benchmark
	public void syncAutocommitInsert(Blackhole bh) {
		final int id = nextId.getAndIncrement();
		final long n = sync.executeUpdate(
				"INSERT INTO dual_ex_t (id, v) VALUES (" + id + ", 's')");
		bh.consume(n);
	}

	@Benchmark
	public void jdbcAutocommitInsert(Blackhole bh) throws Exception {
		final int id = nextId.getAndIncrement();
		final int n = jdbcStatement.executeUpdate(
				"INSERT INTO dual_ex_t (id, v) VALUES (" + id + ", 'j')");
		bh.consume(n);
	}
}