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
package org.genfork.grid.replication.orchid;

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.replication.durable.ChannelDurableIo;
import org.genfork.grid.replication.durable.GroupForceGate;
import org.genfork.grid.replication.metrics.ReplicationMetrics;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * Persists lastCommittedSeq under {@code data-dir/orchid/}.
 * <p>
 * Concurrent {@link #storeLastCommittedSeq} callers share one write+fsync covering the
 * highest requested tip (group commit) via {@link GroupForceGate} — VT-safe park, no
 * {@code Object.wait} across {@link FileChannel#force}. Tip encode reuses a TLS 8-byte buffer
 * (same pattern as {@code EncodeBuffers} scratch).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class FileDurableOrchidStore implements AutoCloseable {
	private static final int STATE_BYTES = 8;
	private static final ThreadLocal<ByteBuffer> TIP_SCRATCH = ThreadLocal.withInitial(
			() -> ByteBuffer.allocate(STATE_BYTES));

	private final FileChannel channel;
	private final boolean fsync;
	private final GroupForceGate forceGate;

	public FileDurableOrchidStore(Path orchidDir, boolean fsync) throws IOException {
		GridFs.createDirs(orchidDir);
		this.fsync = fsync;
		this.channel = ChannelDurableIo.openRw(orchidDir.resolve("state.bin"));
		final long tip = loadLastCommittedSeqUnlocked();
		this.forceGate = new GroupForceGate(tip);
	}

	public long loadLastCommittedSeq() throws IOException {
		final long tip = forceGate.persistedTip();
		return tip > 0L ? tip : loadLastCommittedSeqUnlocked();
	}

	public void storeLastCommittedSeq(long seq) throws IOException {
		if (seq <= 0L) {
			return;
		}
		forceGate.awaitCovered(seq, tip -> {
			writeTip(tip);
			if (fsync) {
				final long t0 = System.nanoTime();
				channel.force(false);
				ReplicationMetrics.recordOplogFsyncNs(System.nanoTime() - t0);
			}
		});
	}

	private void writeTip(long seq) throws IOException {
		final ByteBuffer buf = TIP_SCRATCH.get();
		buf.clear();
		buf.putLong(seq);
		buf.flip();
		ChannelDurableIo.writeAt(channel, 0, buf, false);
		channel.truncate(STATE_BYTES);
	}

	private long loadLastCommittedSeqUnlocked() throws IOException {
		if (channel.size() < STATE_BYTES) {
			return 0L;
		}
		final ByteBuffer buf = TIP_SCRATCH.get();
		buf.clear();
		channel.position(0);
		channel.read(buf);
		buf.flip();
		return buf.getLong();
	}

	@Override
	public void close() throws IOException {
		channel.close();
	}
}
