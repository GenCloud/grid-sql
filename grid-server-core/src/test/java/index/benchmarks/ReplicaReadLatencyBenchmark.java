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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.ReplicaAccessGate;
import org.genfork.grid.sql.client.HostEndpoint;
import org.genfork.grid.sql.client.ReadEndpointSelector;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH: replica-read admission gates + N-endpoint {@link ReadEndpointSelector} (stale skip).
 * <p>
 * Track: {@code mvn -pl grid-server-core -Dtest=ReplicaReadLatencyBenchmark -Pjmh test}
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class ReplicaReadLatencyBenchmark extends AbstractLatencyBenchmark {
	private static final String HOST = "127.0.0.1";
	private static final int BASE_PORT = 15433;
	private static final int MIN_ENDPOINTS = 2;

	@Param({"2", "4", "8"})
	public int endpointCount;

	private List<HostEndpoint> freshEndpoints;
	private List<HostEndpoint> staleSkipEndpoints;
	private ReadEndpointSelector freshSelector;
	private ReadEndpointSelector staleSkipSelector;

	@Setup(Level.Iteration)
	public void setupSelectors() {
		freshEndpoints = buildEndpoints(endpointCount);
		freshSelector = new ReadEndpointSelector(freshEndpoints);
		staleSkipEndpoints = buildEndpoints(endpointCount);
		staleSkipSelector = new ReadEndpointSelector(staleSkipEndpoints);
		// Mark first endpoint stale so acquire must skip when a fresh peer exists.
		staleSkipSelector.markStale(staleSkipEndpoints.get(0), true);
	}

	@Benchmark
	public void linearizableProposer(Blackhole bh) {
		ReplicaAccessGate.ensureLinearizableReadState(true, false, true);
		bh.consume(true);
	}

	@Benchmark
	public void replicaReadSyncedVoter(Blackhole bh) {
		ReplicaAccessGate.ensureReplicaReadState(true, false, true, true);
		bh.consume(true);
	}

	@Benchmark
	public void replicaReadStaleFailClosed(Blackhole bh) {
		try {
			ReplicaAccessGate.ensureReplicaReadState(true, true, true, true);
			bh.consume(false);
		} catch (OrchidNotSyncedException ex) {
			bh.consume(ex);
		}
	}

	@Benchmark
	public void selectorAcquireN(Blackhole bh) {
		final HostEndpoint ep = freshSelector.acquire(true);
		bh.consume(ep);
		freshSelector.release(ep);
	}

	@Benchmark
	public void selectorAcquireSkipStale(Blackhole bh) {
		final HostEndpoint ep = staleSkipSelector.acquire(true);
		bh.consume(ep.port() != BASE_PORT);
		staleSkipSelector.release(ep);
	}

	@Benchmark
	public void selectorRotateAfterFailure(Blackhole bh) {
		final HostEndpoint first = freshSelector.acquire(true);
		final HostEndpoint next = freshSelector.rotateAfterFailure(first);
		bh.consume(next);
		freshSelector.markStale(first, false);
		freshSelector.release(next);
	}

	private static List<HostEndpoint> buildEndpoints(int n) {
		final int count = Math.max(MIN_ENDPOINTS, n);
		final List<HostEndpoint> endpoints = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			endpoints.add(new HostEndpoint(HOST, BASE_PORT + i));
		}
		return endpoints;
	}
}
