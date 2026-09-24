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

import org.genfork.grid.replication.durable.GroupForceGate;
import org.genfork.grid.replication.orchid.FileDurableOrchidStore;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMH: shared group force (gate + orchid tip persist).
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(8)
@State(Scope.Benchmark)
public class GroupForceGateBenchmark extends AbstractLatencyBenchmark {
	private GroupForceGate memoryGate;
	private FileDurableOrchidStore orchidStore;
	private final AtomicLong tip = new AtomicLong(1);

	@Setup
	public void setup() throws Exception {
		memoryGate = new GroupForceGate(0L);
		final Path dir = Files.createTempDirectory("group-force-orchid");
		orchidStore = new FileDurableOrchidStore(dir, true);
	}

	@TearDown
	public void tearDown() throws Exception {
		if (orchidStore != null) {
			orchidStore.close();
		}
	}

	@Benchmark
	public void memoryGroupForce(Blackhole bh) throws Exception {
		final long n = tip.getAndIncrement();
		memoryGate.awaitCovered(n, cover -> {
			// no disk — coalesce cost only
		});
		bh.consume(n);
	}

	@Benchmark
	public void orchidTipGroupPersist(Blackhole bh) throws Exception {
		final long n = tip.getAndIncrement();
		orchidStore.storeLastCommittedSeq(n);
		bh.consume(n);
	}
}
