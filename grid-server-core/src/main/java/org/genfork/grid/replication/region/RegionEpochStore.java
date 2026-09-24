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
package org.genfork.grid.replication.region;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.replication.durable.ChannelDurableIo;

/**
 * Persists region role + epoch under {@code dataDir/region/state.bin}.
 * <p>
 * Layout (LE): magic(int) + version(byte) + role(byte) + epoch(long)
 * + len+claimedByNodeId + len+claimedByDc.
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public final class RegionEpochStore implements AutoCloseable {
	private static final int MAGIC = 0x52474E45;
	private static final byte VERSION = 1;
	private static final int FIXED_HEADER_BYTES = Integer.BYTES + 1 + 1 + Long.BYTES;
	private static final String STATE_FILE = "state.bin";

	private final FileChannel channel;
	private final boolean fsync;

	public RegionEpochStore(Path regionDir, boolean fsync) throws IOException {
		GridFs.createDirs(regionDir);
		this.fsync = fsync;
		this.channel = ChannelDurableIo.openRw(regionDir.resolve(STATE_FILE));
	}

	public RegionLeaseState loadOrDefault(RegionLeaseState bootstrap) throws IOException {
		if (channel.size() < FIXED_HEADER_BYTES) {
			store(bootstrap);
			return bootstrap;
		}
		final ByteBuffer buf = ByteBuffer.allocate((int) Math.min(channel.size(), 4096));
		channel.position(0);
		channel.read(buf);
		buf.flip();
		if (buf.remaining() < FIXED_HEADER_BYTES) {
			return bootstrap;
		}
		final int magic = buf.getInt();
		if (magic != MAGIC) {
			return bootstrap;
		}
		final byte version = buf.get();
		if (version != VERSION) {
			return bootstrap;
		}
		final RegionRole role = RegionRole.fromWire(buf.get());
		final long epoch = buf.getLong();
		final String nodeId = readLenString(buf);
		final String dc = readLenString(buf);
		return new RegionLeaseState(role, epoch, nodeId, dc);
	}

	public void store(RegionLeaseState state) throws IOException {
		final byte[] nodeId = state.claimedByNodeId().getBytes(StandardCharsets.UTF_8);
		final byte[] dc = state.claimedByDc().getBytes(StandardCharsets.UTF_8);
		final int size = FIXED_HEADER_BYTES + Integer.BYTES + nodeId.length + Integer.BYTES + dc.length;
		final ByteBuffer buf = ByteBuffer.allocate(size);
		buf.putInt(MAGIC);
		buf.put(VERSION);
		buf.put(state.role().wireCode());
		buf.putLong(state.epoch());
		buf.putInt(nodeId.length);
		buf.put(nodeId);
		buf.putInt(dc.length);
		buf.put(dc);
		buf.flip();
		ChannelDurableIo.writeAt(channel, 0, buf, fsync);
		channel.truncate(size);
	}

	private static String readLenString(ByteBuffer buf) {
		if (buf.remaining() < Integer.BYTES) {
			return "";
		}
		final int len = buf.getInt();
		if (len < 0 || buf.remaining() < len) {
			return "";
		}
		final byte[] raw = new byte[len];
		buf.get(raw);
		return new String(raw, StandardCharsets.UTF_8);
	}

	@Override
	public void close() throws IOException {
		channel.close();
	}
}