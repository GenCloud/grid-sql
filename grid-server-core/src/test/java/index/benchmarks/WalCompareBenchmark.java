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

import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Side-by-side WAL: OpLog fsync vs RocksDB sync Put (same host disk).
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
@Threads(1)
@State(Scope.Benchmark)
public class WalCompareBenchmark extends AbstractLatencyBenchmark {
	static {
		RocksDB.loadLibrary();
	}

	private OpLog opLog;
	private RocksDB rocks;
	private WriteOptions syncWrite;
	private Path root;
	private final AtomicLong seq = new AtomicLong(1);
	private final byte[] value = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};

	@Setup
	public void setup() throws Exception {
		root = Files.createTempDirectory("wal-compare");
		opLog = new OpLog(root.resolve("oplog"), true);
		final Options opts = new Options().setCreateIfMissing(true);
		rocks = RocksDB.open(opts, root.resolve("rocks").toString());
		syncWrite = new WriteOptions().setSync(true);
	}

	@TearDown
	public void tearDown() throws Exception {
		if (opLog != null) {
			opLog.close();
		}
		if (syncWrite != null) {
			syncWrite.close();
		}
		if (rocks != null) {
			rocks.close();
		}
		System.out.printf("walCompare oplogFsync p50=%d p99=%d%n",
				ReplicationMetrics.oplogFsyncP50Ns(), ReplicationMetrics.oplogFsyncP99Ns());
	}

	@Benchmark
	public void opLogAppendFsync(Blackhole bh) {
		final long n = seq.getAndIncrement();
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"wal", 0, n, ReplicationOpType.UPSERT,
				longKey(n), value, 1L, 0L
		));
		opLog.append(op);
		bh.consume(n);
	}

@Benchmark
	@OperationsPerInvocation(8)
	public void opLogAppendBatch8Fsync(Blackhole bh) {
		final List<ReplicationOp> batch = new ArrayList<>(8);
		for (int i = 0; i < 8; i++) {
			final long n = seq.getAndIncrement();
			batch.add(OpLogCodec.withChecksum(new ReplicationOp(
					"wal_batch", 0, n, ReplicationOpType.UPSERT,
					longKey(n), value, 1L, 0L
			)));
		}
		opLog.appendBatch(batch);
		bh.consume(batch.size());
	}

	@Benchmark
	public void rocksdbPutSync(Blackhole bh) throws RocksDBException {
		final long n = seq.getAndIncrement();
		rocks.put(syncWrite, longKey(n), value);
		bh.consume(n);
	}

	private static byte[] longKey(long n) {
		return new byte[]{
				(byte) n, (byte) (n >> 8), (byte) (n >> 16), (byte) (n >> 24),
				(byte) (n >> 32), (byte) (n >> 40), (byte) (n >> 48), (byte) (n >> 56)
		};
	}
}
