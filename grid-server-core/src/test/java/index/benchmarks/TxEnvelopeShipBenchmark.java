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

import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.tx.TxEnvelopeCodec;
import org.genfork.grid.replication.tx.TxEnvelopeCoordinator;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static index.unit.replication.TxEnvelopeOpFixtures.begin;
import static index.unit.replication.TxEnvelopeOpFixtures.commit;
import static index.unit.replication.TxEnvelopeOpFixtures.upsert;

/**
 * Multi-stream Cross-DC envelope hold/release microbench.
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
@Threads(1)
@State(Scope.Benchmark)
public class TxEnvelopeShipBenchmark extends AbstractLatencyBenchmark {

	private TxEnvelopeCoordinator coord;
	private byte[] membership;
	private final AtomicLong seq = new AtomicLong(1);
	private final AtomicLong txIds = new AtomicLong(1);

	@Setup
	public void setup() {
		coord = new TxEnvelopeCoordinator();
		membership = TxEnvelopeCodec.encode(Set.of(
				new TxEnvelopeCodec.StreamRef("env", 0),
				new TxEnvelopeCodec.StreamRef("env", 1)
		));
	}

	@Benchmark
	public void releaseTwoShardEnvelope(Blackhole bh) {
		final long txId = txIds.getAndIncrement();
		final long s0 = seq.getAndAdd(3);
		final long s1 = seq.getAndAdd(3);
		final List<ReplicationOp> shard0 = List.of(
				begin("env", 0, s0, txId, membership),
				upsert("env", 0, s0 + 1, new byte[]{1}, new byte[]{2}),
				commit("env", 0, s0 + 2, txId)
		);
		final List<ReplicationOp> held = coord.takeShippable(shard0);
		bh.consume(held.size());
		final List<ReplicationOp> shard1 = List.of(
				begin("env", 1, s1, txId, membership),
				upsert("env", 1, s1 + 1, new byte[]{3}, new byte[]{4}),
				commit("env", 1, s1 + 2, txId)
		);
		bh.consume(coord.takeShippable(shard1).size());
	}
}