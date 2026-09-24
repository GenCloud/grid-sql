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
import org.openjdk.jmh.infra.Blackhole;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Streaming SELECT consume (FETCH-window path via Result.map) microbench over TCP loopback.
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
public class SqlWireStreamBenchmark extends AbstractLatencyBenchmark {

	private static final int PORT = 25901;

	private SqlEngine engine;
	private SqlServer server;
	private RemoteConnectionFactory factory;
	private Connection connection;

	@Setup
	public void setup() throws Exception {
		engine = SqlBenchHelper.createEngine(4);
		SqlBenchHelper.ensureKvTable(engine, "stream_t");
		for (int i = 0; i < 500; i++) {
			engine.execute("INSERT INTO stream_t (id, v) VALUES (" + i + ", 'r" + i + "')");
		}
		server = new SqlServer("127.0.0.1", PORT, engine, "u", "p", 32);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		factory = new RemoteConnectionFactory("127.0.0.1", PORT, "u", "p", 32);
		connection = factory.obtain().block();
		connection.createStatement("SELECT id, v FROM stream_t")
				.execute()
				.concatMap(r -> r.map((row, meta) -> row.get(0)))
				.collectList()
				.block(Duration.ofSeconds(30));
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
	public void streamSelectAll(Blackhole bh) {
		final Integer n = connection.createStatement("SELECT id FROM stream_t")
				.execute()
				.concatMap(r -> r.map((row, meta) -> (Integer) ((Number) row.get(0)).intValue()))
				.reduce(0, Integer::sum)
				.block(Duration.ofSeconds(30));
		bh.consume(n);
	}
}
