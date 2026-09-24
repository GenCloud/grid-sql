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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Output;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.codec.duplex.DuplexCodecSupport;
import org.genfork.grid.codec.duplex.DuplexRepairMode;
import org.genfork.grid.serial.RowEncoder;
import org.nustaq.serialization.FSTConfiguration;
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

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

/**
 * Side-by-side encode: catalog {@link RowEncoder} vs Kryo / FST on the same logical layout.
 * Duplex-on paths live in {@link DuplexEncodeOnlyBenchmark} so they cannot pollute the gated score.
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class DuplexCodecBenchmark extends AbstractLatencyBenchmark {
	private TableSchema catalogSchema;
	private Object[] catalogValues;
	private BenchPojo kryoPojo;
	private Kryo kryo;
	private Output kryoOut;
	private FSTConfiguration fst;

	@Setup
	public void setup() {
		catalogSchema = TableSchema.builder("bench")
				.primaryKey("id", SqlType.INT)
				.column("name", SqlType.VARCHAR)
				.column("score", SqlType.DOUBLE)
				.build();
		catalogValues = new Object[]{1, "bench-name", 42.5d};
		kryoPojo = new BenchPojo(1, "bench-name", 42.5d);
		kryo = new Kryo();
		kryo.setRegistrationRequired(false);
		kryo.register(BenchPojo.class);
		kryoOut = new Output(512);
		fst = FSTConfiguration.createDefaultConfiguration();
		fst.registerClass(BenchPojo.class);
		DuplexCodecSupport.configure(false, DuplexRepairMode.FAIL, false, false, 1L, true, true);
	}

	@TearDown
	public void tearDown() {
		if (kryoOut != null) {
			kryoOut.close();
		}
		DuplexCodecSupport.configure(false, DuplexRepairMode.FAIL, false, false, 1L, true, true);
	}

	/** Gated "ours" path: catalog logical bytes (no POJO encode). */
	@Benchmark
	public void logicalToArray(Blackhole bh) {
		bh.consume(RowEncoder.encode(catalogSchema, catalogValues));
	}

	@Benchmark
	public void encodeCatalogLogical(Blackhole bh) {
		bh.consume(RowEncoder.encode(catalogSchema, catalogValues));
	}

	@Benchmark
	public void kryoEncode(Blackhole bh) {
		kryoOut.reset();
		kryo.writeObject(kryoOut, kryoPojo);
		bh.consume(kryoOut.toBytes());
	}

	@Benchmark
	public void fstEncode(Blackhole bh) {
		bh.consume(fst.asByteArray(kryoPojo));
	}

	/** Serializable twin for Kryo/FST only — not a grid domain. */
	public static final class BenchPojo implements Serializable {
		private static final long serialVersionUID = 1L;
		public int id;
		public String name;
		public double score;

		public BenchPojo() {
		}

		public BenchPojo(int id, String name, double score) {
			this.id = id;
			this.name = name;
			this.score = score;
		}
	}
}
