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

import org.genfork.grid.fs.GridFs;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Atomic rewrite latency for catalog-style meta ({@link GridFs#writeAtomic}).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class GridFsWriteAtomicBenchmark extends AbstractLatencyBenchmark {
	private static final byte[] PAYLOAD_PREFIX = "privileges-meta-".getBytes(StandardCharsets.UTF_8);

	private Path target;
	private final AtomicLong seq = new AtomicLong();

	@Setup(Level.Trial)
	public void setupBenchmark() throws Exception {
		final Path dir = Files.createTempDirectory("gridfs-write-atomic-jmh");
		target = dir.resolve("privileges.meta");
		GridFs.writeAtomic(target, PAYLOAD_PREFIX);
	}

	@TearDown(Level.Trial)
	public void tearDownBenchmark() throws Exception {
		if (target != null) {
			final Path parent = target.getParent();
			GridFs.deleteQuietly(target);
			if (parent != null) {
				GridFs.deleteQuietly(parent);
			}
		}
	}

	@Benchmark
	public void writeAtomicReplace(Blackhole bh) throws Exception {
		final long n = seq.incrementAndGet();
		final byte[] body = new byte[PAYLOAD_PREFIX.length + 8];
		System.arraycopy(PAYLOAD_PREFIX, 0, body, 0, PAYLOAD_PREFIX.length);
		body[PAYLOAD_PREFIX.length] = (byte) (n >>> 56);
		body[PAYLOAD_PREFIX.length + 1] = (byte) (n >>> 48);
		body[PAYLOAD_PREFIX.length + 2] = (byte) (n >>> 40);
		body[PAYLOAD_PREFIX.length + 3] = (byte) (n >>> 32);
		body[PAYLOAD_PREFIX.length + 4] = (byte) (n >>> 24);
		body[PAYLOAD_PREFIX.length + 5] = (byte) (n >>> 16);
		body[PAYLOAD_PREFIX.length + 6] = (byte) (n >>> 8);
		body[PAYLOAD_PREFIX.length + 7] = (byte) n;
		GridFs.writeAtomic(target, body);
		bh.consume(body.length);
	}
}
