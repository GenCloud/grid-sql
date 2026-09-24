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

import index.sql.SqlBenchHelper;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.client.Connection;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.client.TxContext;
import org.genfork.grid.sql.netty.SqlServer;
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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One Connection multiplexes N concurrent TxContext commit units (anti-JDBC) over TCP loopback.
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(4)
@State(Scope.Benchmark)
public class SqlTxMultiplexBenchmark extends AbstractLatencyBenchmark {

	private static final int PORT = 25902;

	private SqlEngine engine;
	private SqlServer server;
	private RemoteConnectionFactory factory;
	private Connection connection;
	private final AtomicInteger ids = new AtomicInteger(1);

	@Setup
	public void setup() throws Exception {
		engine = SqlBenchHelper.createEngine(4);
		engine.execute("CREATE TABLE mux (id INT PRIMARY KEY, v VARCHAR)");
		server = new SqlServer("127.0.0.1", PORT, engine, "u", "p", 64);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		factory = new RemoteConnectionFactory("127.0.0.1", PORT, "u", "p", 64);
		connection = factory.obtain().block();
	}

	@TearDown
	public void tearDown() {
		if (connection != null) {
			connection.close().block();
		}
		if (factory != null) {
			factory.dispose();
		}
		if (server != null) {
			server.close();
		}
	}

	@Benchmark
	public void concurrentTxCommit() {
		final int id = ids.getAndIncrement();
		final TxContext tx = connection.begin().block();
		tx.createStatement("INSERT INTO mux (id, v) VALUES (" + id + ", 'x')").execute().blockLast();
		tx.commit().block();
	}
}
