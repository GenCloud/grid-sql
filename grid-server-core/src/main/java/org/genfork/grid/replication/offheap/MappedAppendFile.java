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
package org.genfork.grid.replication.offheap;

import one.nio.util.Cleaner;
import org.genfork.grid.fs.GridFs;
import org.genfork.grid.replication.durable.ChannelDurableIo;
import org.genfork.grid.replication.durable.GroupForceGate;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.genfork.grid.utils.UnsafeMemory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only memory-mapped file. Mapping goes through {@link FileChannel#map}
 * (native {@code map0} under the hood); optional reflective {@code map0} probe kept for JDK variants.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class MappedAppendFile implements AutoCloseable {
	private static final long INITIAL_CAPACITY = 1L << 20;
	private static final Method MAP0;
	private static final String WPOS_SUFFIX = ".wpos";

	static {
		Method map0 = null;
		try {
			final Class<?> impl = Class.forName("sun.nio.ch.FileChannelImpl");
			for (Method m : impl.getDeclaredMethods()) {
				if ("map0".equals(m.getName())) {
					m.setAccessible(true);
					map0 = m;
					break;
				}
			}
		} catch (Throwable ignored) {
		}
		MAP0 = map0;
	}

	private final Path path;
	private final Path wposPath;
	private final boolean fsync;
	private FileChannel channel;
	private FileChannel wposChannel;
	private MappedByteBuffer mapped;
	private OffHeapBuffer buffer;
	private long mappedSize;
	private final AtomicLong writePos = new AtomicLong();
	private final GroupForceGate forceGate;
	private boolean closed;

	public MappedAppendFile(Path path, boolean fsync) throws IOException {
		this.path = path;
		this.wposPath = Path.of(path.toString() + WPOS_SUFFIX);
		this.fsync = fsync;
		GridFs.createParentDirs(path);
		this.channel = FileChannel.open(
				path,
				StandardOpenOption.CREATE,
				StandardOpenOption.READ,
				StandardOpenOption.WRITE
		);
		this.wposChannel = ChannelDurableIo.openRw(wposPath);
		final long fileSize = channel.size();
		long logical = fileSize;
		if (GridFs.exists(wposPath)) {
			try {
				logical = Long.parseLong(GridFs.readString(wposPath).trim());
			} catch (Exception ignored) {
			}
		}
		if (logical < 0) {
			logical = 0;
		}
		if (fileSize > 0) {
			logical = Math.min(logical, fileSize);
		} else {
			logical = 0;
		}
		final long mapSize = Math.max(fileSize, INITIAL_CAPACITY);
		remap(mapSize);
		writePos.set(logical);
		this.forceGate = new GroupForceGate(logical);
		new Cleaner(this) {
			@Override
			public void clear() {
				try {
					MappedAppendFile.this.close();
				} catch (IOException ignored) {
				}
			}
		};
	}

	public long writePosition() {
		return writePos.get();
	}

	public long append(long srcAddr, int length) throws IOException {
		ensureCapacity(writePos.get() + length);
		final long offset = writePos.get();
		UnsafeMemory.copy(null, srcAddr, null, buffer.address() + offset, length);
		final long end = writePos.addAndGet(length);
		if (fsync) {
			groupForce(end);
		}
		return offset;
	}

	/** Append without durability; caller must {@link #sync()} before acknowledging durability. */
	public long appendDeferred(long srcAddr, int length) throws IOException {
		ensureCapacity(writePos.get() + length);
		final long offset = writePos.get();
		UnsafeMemory.copy(null, srcAddr, null, buffer.address() + offset, length);
		writePos.addAndGet(length);
		return offset;
	}

	/** Force dirty bytes to disk (no-op when constructed with fsync=false). */
	public void sync() throws IOException {
		if (!fsync) {
			return;
		}
		groupForce(writePos.get());
	}

	/**
	 * Group fsync: concurrent waiters share one {@link FileChannel#force} covering dirty bytes.
	 */
	private void groupForce(long coverThrough) throws IOException {
		forceGate.awaitCovered(coverThrough, tip -> {
			final long t0 = System.nanoTime();
			channel.force(false);
			ReplicationMetrics.recordOplogFsyncNs(System.nanoTime() - t0);
			final long pos = writePos.get();
			persistWpos(pos);
		});
	}

	public OffHeapBuffer view(long offset, int length) {
		if (offset < 0 || offset + length > writePos.get()) {
			throw new IllegalArgumentException("view out of range offset=" + offset + " len=" + length);
		}
		return OffHeapBuffer.wrapMappedAddress(buffer.address() + offset, length, mapped);
	}

	public long size() {
		return writePos.get();
	}

	/**
	 * Truncate logical length after torn-tail recovery (crash left zeros / partial record).
	 * Persists {@code .wpos}; physical {@link FileChannel#truncate} is best-effort on close.
	 */
	public void truncateLogical(long newLogicalSize) throws IOException {
		if (newLogicalSize < 0L || newLogicalSize > writePos.get()) {
			throw new IllegalArgumentException(
					"truncateLogical out of range new=" + newLogicalSize + " size=" + writePos.get());
		}
		writePos.set(newLogicalSize);
		forceGate.resetTip(Math.min(forceGate.persistedTip(), newLogicalSize));
		persistWpos(newLogicalSize);
	}

	/**
	 * Flush dirty mmap pages and persist logical {@code .wpos} even when constructed with
	 * {@code fsync=false}. Used by graceful SIGTERM / Spring destroy — not a substitute for
	 * SIGKILL / SIGSEGV (those rely on torn-tail recovery on next open).
	 */
	public void forceDurableCheckpoint() throws IOException {
		if (closed) {
			return;
		}
		if (mapped != null) {
			mapped.force();
		}
		if (channel != null) {
			channel.force(false);
		}
		final long pos = writePos.get();
		persistWpos(pos);
		forceGate.resetTip(pos);
	}

	private void persistWpos(long logical) throws IOException {
		final byte[] bytes = Long.toString(logical).getBytes(StandardCharsets.US_ASCII);
		final ByteBuffer buf = ByteBuffer.wrap(bytes);
		ChannelDurableIo.writeAt(wposChannel, 0, buf, false);
		wposChannel.truncate(bytes.length);
	}

	public void rewriteFrom(long srcAddr, long length) throws IOException {
		remap(Math.max(length, INITIAL_CAPACITY));
		if (length > 0) {
			UnsafeMemory.copy(null, srcAddr, null, buffer.address(), length);
		}
		writePos.set(length);
		if (fsync) {
			mapped.force();
		}
	}

	private void ensureCapacity(long required) throws IOException {
		if (required <= mappedSize) {
			return;
		}
		long next = Math.max(mappedSize, INITIAL_CAPACITY);
		while (next < required) {
			next <<= 1;
		}
		remap(next);
	}

	private void remap(long newSize) throws IOException {
		if (mapped != null && fsync) {
			mapped.force();
		}
		final MappedByteBuffer nextMapped = map(channel, newSize);
		this.mapped = nextMapped;
		this.mappedSize = newSize;
		this.buffer = OffHeapBuffer.wrapMappedAddress(OffHeapBuffer.addressOf(nextMapped), newSize, nextMapped);
	}

	private static MappedByteBuffer map(FileChannel channel, long size) throws IOException {
		// FileChannel.map extends file if needed and invokes native map0.
		if (MAP0 != null) {
			try {
				if (MAP0.getParameterCount() == 3) {
					MAP0.invoke(channel, 1, 0L, size);
				} else if (MAP0.getParameterCount() == 4) {
					MAP0.invoke(channel, 1, 0L, size, false);
				}
			} catch (Throwable ignored) {
				// still use public map below as authoritative MappedByteBuffer
			}
		}
		return channel.map(FileChannel.MapMode.READ_WRITE, 0, size);
	}

	@Override
	public void close() throws IOException {
		if (closed) {
			return;
		}
		closed = true;
		// Always force on close (graceful SIGTERM / Spring destroy) even if per-append fsync=false.
		if (mapped != null) {
			mapped.force();
		}
		final long logical = writePos.get();
		if (channel != null) {
			try {
				channel.force(false);
			} catch (IOException ignored) {
				// still persist .wpos / best-effort truncate below
			}
		}
		persistWpos(logical);
		GridFs.unmap(mapped);
		mapped = null;
		buffer = null;
		if (wposChannel != null) {
			try {
				wposChannel.close();
			} catch (IOException ignored) {
			}
			wposChannel = null;
		}
		if (channel != null) {
			try {
				channel.truncate(logical);
			} catch (IOException ignored) {
				// Windows may refuse truncate while mapping still referenced; .wpos is authoritative
			}
			channel.close();
			channel = null;
		}
	}
}
