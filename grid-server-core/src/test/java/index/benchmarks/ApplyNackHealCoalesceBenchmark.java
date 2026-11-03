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

import org.genfork.grid.replication.util.ApplyNackHealUtil;
import org.genfork.grid.replication.util.ApplyNackHealUtil.CoalesceState;
import org.genfork.grid.replication.util.CatalogLagFenceUtil;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micro-stamp: APPLY_NACK heal coalesce decision + catalog lag fence predicates.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Thread)
public class ApplyNackHealCoalesceBenchmark {

	private CoalesceState state;
	private AtomicLong seq;
	private long now;

	@Setup
	public void setup() {
		state = new CoalesceState();
		seq = new AtomicLong(1L);
		now = System.currentTimeMillis();
	}

	@Benchmark
	public void shouldScheduleHeal(Blackhole bh) {
		final long s = seq.incrementAndGet();
		bh.consume(ApplyNackHealUtil.shouldScheduleHeal(
				state, "bench", 0, s, now + s, ApplyNackHealUtil.APPLY_NACK_HEAL_INTERVAL_MS, false, false));
	}

	@Benchmark
	public void catalogLagFence(Blackhole bh) {
		bh.consume(CatalogLagFenceUtil.markApplyLagStale(1L));
		bh.consume(CatalogLagFenceUtil.denyDdlMint(0L, 5L, 6L));
		bh.consume(CatalogLagFenceUtil.readinessDownForStuckLag(
				1L, CatalogLagFenceUtil.CATALOG_LAG_STUCK_MS, CatalogLagFenceUtil.CATALOG_LAG_STUCK_MS));
	}
}
