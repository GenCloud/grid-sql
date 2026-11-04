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

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import org.genfork.grid.sql.client.FactoryAuthRedirectUtil;
import org.genfork.grid.sql.client.FactoryServerMetaPublish;
import org.genfork.grid.sql.client.ServerMeta;
import org.genfork.grid.sql.client.SessionRole;

/**
 * Client AUTH redirect / meta-publish micro-stamp (applyLagStale fail-closed path).
 * <p>
 * Informational compare — not a living capacity floor. Run calm, not co-scheduled with QG.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class FactoryAuthRedirectBenchmark extends AbstractLatencyBenchmark {

	private static final String NODE = "n1";
	private static final long SCHEMA_EPOCH = 1L;
	private static final int REDIRECT_BUDGET = 2;
	private static final int CONNECTED_INDEX = 0;
	private static final int HINT_INDEX = 1;

	private ServerMeta staleWriter;
	private ServerMeta freshWriter;
	private ServerMeta freshFollower;

	@org.openjdk.jmh.annotations.Setup
	public void setup() {
		staleWriter = new ServerMeta(NODE, true, "", true, SCHEMA_EPOCH);
		freshWriter = new ServerMeta(NODE, true, "", false, SCHEMA_EPOCH);
		freshFollower = new ServerMeta(NODE, false, "n2", false, SCHEMA_EPOCH);
	}

	@Benchmark
	public void decideAfterApplyLagStale(Blackhole bh) {
		bh.consume(FactoryAuthRedirectUtil.decideAfterApplyLagStale(REDIRECT_BUDGET));
		bh.consume(FactoryAuthRedirectUtil.decideAfterApplyLagStale(0));
	}

	@Benchmark
	public void decidePrimaryFresh(Blackhole bh) {
		bh.consume(FactoryAuthRedirectUtil.decidePrimaryFresh(
				freshWriter, REDIRECT_BUDGET, CONNECTED_INDEX, HINT_INDEX));
		bh.consume(FactoryAuthRedirectUtil.decidePrimaryFresh(
				freshFollower, REDIRECT_BUDGET, CONNECTED_INDEX, HINT_INDEX));
	}

	@Benchmark
	public void shouldPublishSkipsStale(Blackhole bh) {
		bh.consume(FactoryServerMetaPublish.shouldPublish(SessionRole.PRIMARY, staleWriter));
		bh.consume(FactoryServerMetaPublish.shouldPublish(SessionRole.PRIMARY, freshWriter));
	}
}
