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
package index.replication;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.FileDurableOpStore;
import org.genfork.grid.replication.offheap.MappedAppendFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Torn-tail OpLog recovery: zero length header after crash must not block boot.
 * Graceful close persists atomic {@code .wpos} so HA restart needs no volume purge.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class OpLogTornTailRecoveryTest {
	private static final String STREAM = "torn_0";
	private static final String DOMAIN = "t";
	private static final int SHARD = 0;
	private static final int TORN_ZERO_HEADER_BYTES = 8;
	private static final long SCHEMA_EPOCH = 1L;
	private static final String WPOS_SUFFIX = ".wpos";

	@TempDir
	Path dir;

	@Test
	void replayTruncatesZeroLengthTailAndKeepsPriorOps() throws Exception {
		long goodSize;
		try (FileDurableOpStore store = new FileDurableOpStore(dir, STREAM, false)) {
			store.append(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD, 1L, ReplicationOpType.UPSERT, new byte[]{1}, new byte[]{2}, SCHEMA_EPOCH, 0L)));
			store.append(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD, 2L, ReplicationOpType.UPSERT, new byte[]{3}, new byte[]{4}, SCHEMA_EPOCH, 0L)));
		}
		final Path logPath = dir.resolve(STREAM + ".log");
		try (MappedAppendFile mapped = new MappedAppendFile(logPath, false)) {
			goodSize = mapped.size();
		}
		try (FileChannel ch = FileChannel.open(logPath, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
			final ByteBuffer buf = ByteBuffer.allocate(TORN_ZERO_HEADER_BYTES);
			buf.putInt(0);
			buf.putInt(0);
			buf.flip();
			ch.write(buf);
		}
		final Path wpos = Path.of(logPath + WPOS_SUFFIX);
		Files.writeString(wpos, Long.toString(Files.size(logPath)));

		final List<ReplicationOp> ops;
		try (FileDurableOpStore store = new FileDurableOpStore(dir, STREAM, false)) {
			ops = store.replayOps();
		}
		assertEquals(2, ops.size());
		assertEquals(1L, ops.get(0).opSeq());
		assertEquals(2L, ops.get(1).opSeq());
		try (MappedAppendFile mapped = new MappedAppendFile(logPath, false)) {
			assertEquals(goodSize, mapped.size());
			assertTrue(mapped.size() > 0L);
		}
	}

	@Test
	void gracefulClosePersistsAtomicWposMatchingLogicalSize() throws Exception {
		final Path logPath = dir.resolve(STREAM + ".log");
		try (FileDurableOpStore store = new FileDurableOpStore(dir, STREAM, false)) {
			store.append(OpLogCodec.withChecksum(new ReplicationOp(
					DOMAIN, SHARD, 1L, ReplicationOpType.UPSERT, new byte[]{9}, new byte[]{8}, SCHEMA_EPOCH, 0L)));
		}
		final long logical;
		try (MappedAppendFile mapped = new MappedAppendFile(logPath, false)) {
			logical = mapped.size();
			mapped.forceDurableCheckpoint();
		}
		final Path wpos = Path.of(logPath + WPOS_SUFFIX);
		assertTrue(Files.exists(wpos));
		final String raw = Files.readString(wpos, StandardCharsets.UTF_8).trim();
		assertEquals(Long.toString(logical), raw);
		assertTrue(logical > 0L);
	}
}