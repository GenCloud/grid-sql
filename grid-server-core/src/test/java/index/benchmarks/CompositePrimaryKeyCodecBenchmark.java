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

import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.serial.PrimaryKeyCodec;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Measures scalar-compatible and composite PRIMARY KEY wire encoding.
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class CompositePrimaryKeyCodecBenchmark {
	private static final int WORLD_ID = 7;
	private static final long OBJECT_ID = 10_000_001L;

	private final TableSchema scalarSchema = TableSchema.builder("scalar_key")
			.primaryKey("id", SqlType.BIGINT)
			.build();
	private final TableSchema compositeSchema = TableSchema.builder("composite_key")
			.primaryKey("world_id", SqlType.INT)
			.primaryKey("object_id", SqlType.BIGINT)
			.build();

	@Benchmark
	public byte[] encodeScalarKey() {
		return PrimaryKeyCodec.encodeValues(scalarSchema, new Object[]{OBJECT_ID});
	}

	@Benchmark
	public byte[] encodeCompositeKey() {
		return PrimaryKeyCodec.encodeValues(compositeSchema, new Object[]{WORLD_ID, OBJECT_ID});
	}
}
