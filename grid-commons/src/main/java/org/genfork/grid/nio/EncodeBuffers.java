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
package org.genfork.grid.nio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Little-endian encode/decode buffers with <strong>per-site</strong> heap vs direct choice.
 * <p>
 * Site policy:
 * <ul>
 *   <li><strong>WireTiny</strong> — SQL/repl frames that materialize {@code byte[]} via
 *   {@link #toByteArray} → {@link #allocateWireLe(int)} / {@link #allocateHeapLe(int)} (heap).
 *   Measured stamp {@code 2026-09-15-gaps-features}: ~67 ns heap vs ~2.3 µs direct on tiny EXEC.</li>
 *   <li><strong>WireLarge / encodeInto</strong> — write LE directly into an existing
 *   {@link ByteBuffer} or Netty pooled {@code ByteBuf} without {@link #toByteArray};
 *   prefer direct / pooled until JMH proves otherwise ({@code DirectVsHeapEncodeIntoBenchmark}).</li>
 * </ul>
 * Global {@code grid.encode.direct} only affects {@link #allocateLe(int)} (compat / JMH override).
 * Tiny wire stays heap until encodeInto wins p50/p99 on the product path.
 *
 * Lives in {@code grid-commons} (shared by client and server; JDK-only).
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public final class EncodeBuffers {
	private static final boolean DEFAULT_DIRECT = resolveDefaultDirect();

	private EncodeBuffers() {
	}

	private static boolean resolveDefaultDirect() {
		final String prop = System.getProperty("grid.encode.direct");
		if (prop != null && !prop.isBlank()) {
			return Boolean.parseBoolean(prop.trim());
		}
		final String env = System.getenv("GRID_ENCODE_DIRECT");
		if (env != null && !env.isBlank()) {
			return Boolean.parseBoolean(env.trim());
		}
		// Wire product path prefers heap (see allocateWireLe); keep override for experiments.
		return false;
	}

	/** Resolved {@code grid.encode.direct} / env (compat). Prefer site-specific allocate*. */
	public static boolean preferDirect() {
		return DEFAULT_DIRECT;
	}

	/**
	 * SQL / replication wire frames that materialize {@code byte[]} for Netty (WireTiny).
	 * Always heap — proven faster than direct when {@link #toByteArray} is required.
	 */
	public static ByteBuffer allocateWireLe(int size) {
		return allocateLe(size, false);
	}

	/** Heap LE — tiny length-prefixed structs / catalogs that call {@link #toByteArray} or {@link ByteBuffer#array()}. */
	public static ByteBuffer allocateHeapLe(int size) {
		return allocateLe(size, false);
	}

	/**
	 * Direct LE — WireLarge off-heap staging or encodeInto paths that avoid {@link #toByteArray}.
	 */
	public static ByteBuffer allocateDirectLe(int size) {
		return allocateLe(size, true);
	}

	/**
	 * Compat allocate: respects {@code grid.encode.direct} (default false = heap).
	 * Prefer {@link #allocateWireLe(int)} / {@link #allocateHeapLe(int)} / {@link #allocateDirectLe(int)}.
	 */
	public static ByteBuffer allocateLe(int size) {
		return allocateLe(size, DEFAULT_DIRECT);
	}

	/** Explicit mode for JMH / tests (does not change process default). */
	public static ByteBuffer allocateLe(int size, boolean direct) {
		final ByteBuffer buf = direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size);
		return buf.order(ByteOrder.LITTLE_ENDIAN);
	}

	/**
	 * Write a little-endian {@code int} at the current position.
	 * Ensures LE regardless of {@code buf.order()} (restores prior order).
	 */
	public static void putIntLe(ByteBuffer buf, int value) {
		final ByteOrder prev = buf.order();
		buf.order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(value);
		buf.order(prev);
	}

	/**
	 * Write a little-endian {@code short} at the current position.
	 * Ensures LE regardless of {@code buf.order()} (restores prior order).
	 */
	public static void putShortLe(ByteBuffer buf, short value) {
		final ByteOrder prev = buf.order();
		buf.order(ByteOrder.LITTLE_ENDIAN);
		buf.putShort(value);
		buf.order(prev);
	}

	/**
	 * Write a little-endian {@code long} at the current position.
	 * Ensures LE regardless of {@code buf.order()} (restores prior order).
	 */
	public static void putLongLe(ByteBuffer buf, long value) {
		final ByteOrder prev = buf.order();
		buf.order(ByteOrder.LITTLE_ENDIAN);
		buf.putLong(value);
		buf.order(prev);
	}

	/**
	 * Snapshot bytes written into {@code buf} from position 0 to current position.
	 * Heap buffers with zero arrayOffset reuse the backing array when fully filled
	 * from the start with no leftover capacity beyond position.
	 */
	public static byte[] toByteArray(ByteBuffer buf) {
		final int pos = buf.position();
		if (buf.hasArray() && buf.arrayOffset() == 0 && pos == buf.capacity()) {
			return buf.array();
		}
		final byte[] out = new byte[pos];
		final ByteBuffer dup = buf.duplicate().order(buf.order());
		dup.clear();
		dup.limit(pos);
		dup.get(out);
		return out;
	}

	/** Decode wrap: little-endian view over inbound {@code byte[]} (matches LE encode). */
	public static ByteBuffer wrapLe(byte[] body) {
		return ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
	}

	/**
	 * Length-prefixed blob: {@code int32 len} + {@code len} bytes (LE).
	 * Shared by SQL / replication WireTiny frames.
	 */
	public static void putLengthPrefixed(ByteBuffer buf, byte[] data) {
		buf.putInt(data.length);
		buf.put(data);
	}

	/**
	 * Read length-prefixed blob written by {@link #putLengthPrefixed}.
	 * Caller must ensure remaining bytes are sufficient (legacy wire trust).
	 */
	public static byte[] getLengthPrefixed(ByteBuffer buf) {
		final int len = buf.getInt();
		final byte[] data = new byte[len];
		buf.get(data);
		return data;
	}

	/** UTF-8 string as length-prefixed bytes ({@link #putLengthPrefixed}). */
	public static void putUtf8(ByteBuffer buf, String value) {
		putLengthPrefixed(buf, value.getBytes(StandardCharsets.UTF_8));
	}

	/** Decode UTF-8 string written by {@link #putUtf8} / {@link #putLengthPrefixed}. */
	public static String getUtf8(ByteBuffer buf) {
		return new String(getLengthPrefixed(buf), StandardCharsets.UTF_8);
	}
}
