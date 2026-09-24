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
package org.genfork.grid.replication.durable;

import org.genfork.grid.replication.offheap.MappedAppendFile;
import org.genfork.grid.replication.offheap.OffHeapBuffer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Length-prefixed (int32 + payload) append log over {@link MappedAppendFile}, with optional group fsync.
 * Shared by OpLog stores; batch append does one sync at the end.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class LengthPrefixedAppendLog implements AutoCloseable {
	/** Wire framing: little-endian int32 length before payload. */
	public static final int LENGTH_HEADER_BYTES = 4;

	private final ReentrantLock lock = new ReentrantLock();
	private final MappedAppendFile mapped;
	private final boolean fsync;

	public LengthPrefixedAppendLog(Path logFile, boolean fsync) throws IOException {
		this.fsync = fsync;
		this.mapped = new MappedAppendFile(logFile, fsync);
	}

	public MappedAppendFile mapped() {
		return mapped;
	}

	public long appendBytes(byte[] payload) throws IOException {
		lock.lock();
		try (OffHeapBuffer record = OffHeapBuffer.allocate((long) LENGTH_HEADER_BYTES + payload.length)) {
			record.putInt(payload.length);
			record.putBytes(payload);
			return mapped.append(record.address(), (int) record.position());
		} finally {
			lock.unlock();
		}
	}

	public long appendOffHeap(OffHeapBuffer payload) throws IOException {
		lock.lock();
		try (OffHeapBuffer record = OffHeapBuffer.allocate((long) LENGTH_HEADER_BYTES + payload.position())) {
			final int payloadLen = (int) payload.position();
			record.putInt(payloadLen);
			record.putBytes(payload.address(), payloadLen);
			return mapped.append(record.address(), (int) record.position());
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Append without durability; caller must {@link #sync()} before acknowledging durable commit.
	 */
	public long appendOffHeapDeferred(OffHeapBuffer payload) throws IOException {
		lock.lock();
		try (OffHeapBuffer record = OffHeapBuffer.allocate((long) LENGTH_HEADER_BYTES + payload.position())) {
			final int payloadLen = (int) payload.position();
			record.putInt(payloadLen);
			record.putBytes(payload.address(), payloadLen);
			return mapped.appendDeferred(record.address(), (int) record.position());
		} finally {
			lock.unlock();
		}
	}

	/** Force dirty bytes (group fsync when store constructed with fsync=true). */
	public void sync() throws IOException {
		lock.lock();
		try {
			mapped.sync();
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Encode each item to off-heap payload, wrap as length-prefixed records, one group fsync.
	 * @return file offsets per item
	 */
	public <T> long[] appendBatchEncoded(List<T> items, Function<T, OffHeapBuffer> encode) throws IOException {
		if (items == null || items.isEmpty()) {
			return new long[0];
		}
		lock.lock();
		try {
			final long[] offsets = new long[items.size()];
			for (int i = 0; i < items.size(); i++) {
				try (OffHeapBuffer encoded = encode.apply(items.get(i))) {
					final int payloadLen = (int) encoded.position();
					try (OffHeapBuffer record = OffHeapBuffer.allocate((long) LENGTH_HEADER_BYTES + payloadLen)) {
						record.putInt(payloadLen);
						record.putBytes(encoded.address(), payloadLen);
						if (fsync) {
							offsets[i] = mapped.appendDeferred(record.address(), (int) record.position());
						} else {
							offsets[i] = mapped.append(record.address(), (int) record.position());
						}
					}
				}
			}
			if (fsync) {
				mapped.sync();
			}
			return offsets;
		} finally {
			lock.unlock();
		}
	}

	public long size() {
		return mapped.size();
	}

	/** Truncate after torn-tail recovery (delegates to {@link MappedAppendFile#truncateLogical}). */
	public void truncateLogical(long newLogicalSize) throws IOException {
		lock.lock();
		try {
			mapped.truncateLogical(newLogicalSize);
		} finally {
			lock.unlock();
		}
	}

	/** Graceful shutdown flush — see {@link MappedAppendFile#forceDurableCheckpoint()}. */
	public void forceDurableCheckpoint() throws IOException {
		lock.lock();
		try {
			mapped.forceDurableCheckpoint();
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void close() throws IOException {
		lock.lock();
		try {
			mapped.close();
		} finally {
			lock.unlock();
		}
	}
}
