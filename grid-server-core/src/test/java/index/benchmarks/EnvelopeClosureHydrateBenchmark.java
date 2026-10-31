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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.genfork.grid.replication.apply.ReplicaApplier;
import org.genfork.grid.replication.tx.TxEnvelopeCodec;
import org.genfork.grid.replication.tx.TxEnvelopeCoordinator;
import org.genfork.grid.replication.util.EnvelopeClosureHydrateUtil;
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

/**
 * Envelope-closure hydrate abort path (orphan membership clear) — sealed at-risk stamp.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Thread)
public class EnvelopeClosureHydrateBenchmark extends AbstractLatencyBenchmark {
	private static final String DOMAIN_A = "bench_env_a";
	private static final String DOMAIN_B = "bench_env_b";
	private static final long TX_ID = 0x0eb1L;

	private TxEnvelopeCoordinator gate;
	private Map<String, ReplicaApplier> appliers;

	@Setup
	public void setup() {
		gate = new TxEnvelopeCoordinator();
		// HashMap allows null applier stubs (orphan abort path without live openTx).
		appliers = new HashMap<>();
		appliers.put(DOMAIN_A, null);
		appliers.put(DOMAIN_B, null);
	}

	@Benchmark
	public void orphanAbortClosure(Blackhole bh) {
		final byte[] membership = TxEnvelopeCodec.encode(List.of(
				new TxEnvelopeCodec.StreamRef(DOMAIN_A, 0),
				new TxEnvelopeCodec.StreamRef(DOMAIN_B, 0)));
		gate.registerApply(TxEnvelopeCodec.decode(TX_ID, membership));
		gate.noteApplyCommit(TX_ID, new TxEnvelopeCodec.StreamRef(DOMAIN_A, 0));
		EnvelopeClosureHydrateUtil.completeOrAbortAfterStreamHydrate(
				gate, appliers, DOMAIN_A, 0, (d, s, a) -> {
				});
		bh.consume(gate.hasOpenApplyMembershipForStream(DOMAIN_A, 0));
		bh.consume(membership.length);
		bh.consume("pad".getBytes(StandardCharsets.UTF_8));
	}

	@Benchmark
	public void prepareSealStickyClear(Blackhole bh) {
		final byte[] membership = TxEnvelopeCodec.encode(List.of(
				new TxEnvelopeCodec.StreamRef(DOMAIN_A, 0),
				new TxEnvelopeCodec.StreamRef(DOMAIN_B, 0)));
		gate.registerApply(TxEnvelopeCodec.decode(TX_ID + 1L, membership));
		gate.noteApplyCommit(TX_ID + 1L, new TxEnvelopeCodec.StreamRef(DOMAIN_A, 0));
		gate.noteApplyCommit(TX_ID + 1L, new TxEnvelopeCodec.StreamRef(DOMAIN_B, 0));
		EnvelopeClosureHydrateUtil.prepareSealForDomain(
				gate, appliers, DOMAIN_A, List.of(0), (d, s, a) -> {
				});
		bh.consume(gate.hasOpenApplyMembershipForStream(DOMAIN_A, 0));
		bh.consume(membership.length);
	}
}
