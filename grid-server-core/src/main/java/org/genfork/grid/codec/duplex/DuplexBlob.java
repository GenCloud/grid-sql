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
package org.genfork.grid.codec.duplex;

import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Duplex value blob: packed dataLane + complementary parityLane.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public final class DuplexBlob {
	/** Wire magic "DPX1". */
	public static final int WIRE_MAGIC = 0x44505831;

	private final byte[] dataLane;
	private final byte[] parityLane;
	private final int logicalLen;
	private final long schemaEpoch;
	private final long contentChecksum;

	public DuplexBlob(byte[] dataLane, byte[] parityLane, int logicalLen, long schemaEpoch, long contentChecksum) {
		this.dataLane = Arrays.copyOf(dataLane, dataLane.length);
		this.parityLane = Arrays.copyOf(parityLane, parityLane.length);
		this.logicalLen = logicalLen;
		this.schemaEpoch = schemaEpoch;
		this.contentChecksum = contentChecksum;
	}

	public static boolean isWire(byte[] wire) {
		return wire != null && wire.length >= 4 && getInt(wire, 0) == WIRE_MAGIC;
	}

	public byte[] dataLane() {
		return Arrays.copyOf(dataLane, dataLane.length);
	}

	public byte[] parityLane() {
		return Arrays.copyOf(parityLane, parityLane.length);
	}

	public int logicalLen() {
		return logicalLen;
	}

	public long schemaEpoch() {
		return schemaEpoch;
	}

	public static long checksum(byte[] dataLane, byte[] parityLane, int logicalLen, long schemaEpoch) {
		final CRC32 crc = new CRC32();
		crc.update(dataLane);
		crc.update(parityLane);
		crc.update((logicalLen >>> 24) & 0xFF);
		crc.update((logicalLen >>> 16) & 0xFF);
		crc.update((logicalLen >>> 8) & 0xFF);
		crc.update(logicalLen & 0xFF);
		crc.update((int) (schemaEpoch >>> 56) & 0xFF);
		crc.update((int) (schemaEpoch >>> 48) & 0xFF);
		crc.update((int) (schemaEpoch >>> 40) & 0xFF);
		crc.update((int) (schemaEpoch >>> 32) & 0xFF);
		crc.update((int) (schemaEpoch >>> 24) & 0xFF);
		crc.update((int) (schemaEpoch >>> 16) & 0xFF);
		crc.update((int) (schemaEpoch >>> 8) & 0xFF);
		crc.update((int) schemaEpoch & 0xFF);
		return crc.getValue();
	}

	/**
	 * Wire format: magic, int logicalLen, long schemaEpoch, long checksum, int laneLen, dataLane, parityLane.
	 */
	public byte[] toWireBytes() {
		final int laneLen = dataLane.length;
		final byte[] wire = new byte[4 + 4 + 8 + 8 + 4 + laneLen + laneLen];
		int p = 0;
		p = putInt(wire, p, WIRE_MAGIC);
		p = putInt(wire, p, logicalLen);
		p = putLong(wire, p, schemaEpoch);
		p = putLong(wire, p, contentChecksum);
		p = putInt(wire, p, laneLen);
		System.arraycopy(dataLane, 0, wire, p, laneLen);
		p += laneLen;
		System.arraycopy(parityLane, 0, wire, p, laneLen);
		return wire;
	}

	public static DuplexBlob fromWireBytes(byte[] wire) {
		int p = 0;
		final int magic = getInt(wire, p);
		p += 4;
		if (magic != WIRE_MAGIC) {
			throw new IllegalArgumentException("Not a DuplexBlob wire payload");
		}
		final int logicalLen = getInt(wire, p);
		p += 4;
		final long schemaEpoch = getLong(wire, p);
		p += 8;
		final long checksum = getLong(wire, p);
		p += 8;
		final int laneLen = getInt(wire, p);
		p += 4;
		final byte[] data = Arrays.copyOfRange(wire, p, p + laneLen);
		p += laneLen;
		final byte[] parity = Arrays.copyOfRange(wire, p, p + laneLen);
		final long expected = checksum(data, parity, logicalLen, schemaEpoch);
		if (expected != checksum) {
			throw new IllegalStateException("DuplexBlob content checksum mismatch");
		}
		return new DuplexBlob(data, parity, logicalLen, schemaEpoch, checksum);
	}

	private static int putInt(byte[] a, int p, int v) {
		a[p++] = (byte) (v >>> 24);
		a[p++] = (byte) (v >>> 16);
		a[p++] = (byte) (v >>> 8);
		a[p++] = (byte) v;
		return p;
	}

	private static int putLong(byte[] a, int p, long v) {
		for (int i = 7; i >= 0; i--) {
			a[p++] = (byte) (v >>> (i * 8));
		}
		return p;
	}

	private static int getInt(byte[] a, int p) {
		return ((a[p] & 0xFF) << 24) | ((a[p + 1] & 0xFF) << 16) | ((a[p + 2] & 0xFF) << 8) | (a[p + 3] & 0xFF);
	}

	private static long getLong(byte[] a, int p) {
		long v = 0;
		for (int i = 0; i < 8; i++) {
			v = (v << 8) | (a[p + i] & 0xFF);
		}
		return v;
	}
}
