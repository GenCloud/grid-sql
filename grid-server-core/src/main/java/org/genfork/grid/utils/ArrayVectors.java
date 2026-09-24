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
package org.genfork.grid.utils;

import java.util.zip.CRC32;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Vector API helpers for thick byte[] kernels (equals / compare / CRC32).
 * <p>
 * {@link ArrayUtil#fastHash(byte[])} stays scalar to preserve the hash contract.
 * CRC32 uses {@link java.util.zip.CRC32} for bit-for-bit compatibility and does
 * not touch Vector lanes (safe when {@code jdk.incubator.vector} is not added).
 * Vector kernels require {@code --add-modules=jdk.incubator.vector} (holder class
 * keeps class-init of this type free of Vector resolution).
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public final class ArrayVectors {
	private static final int CRC32_EMPTY_VALUE = 0;

	private ArrayVectors() {
	}

	/**
	 * Lazy Vector species — loaded only when equals / compare / xor touch it.
	 */
	private static final class VectorLane {
		private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
	}

	/** Vectorized equals with scalar tail; null-safe. */
	public static boolean bytesEqual(byte[] a, byte[] b) {
		if (a == b) {
			return true;
		}
		if (a == null || b == null || a.length != b.length) {
			return false;
		}
		final VectorSpecies<Byte> species = VectorLane.SPECIES;
		final int length = a.length;
		final int upper = species.loopBound(length);
		int i = 0;
		for (; i < upper; i += species.length()) {
			final ByteVector va = ByteVector.fromArray(species, a, i);
			final ByteVector vb = ByteVector.fromArray(species, b, i);
			if (!va.eq(vb).allTrue()) {
				return false;
			}
		}
		for (; i < length; i++) {
			if (a[i] != b[i]) {
				return false;
			}
		}
		return true;
	}

	/** Unsigned lexicographic compare; vectorized mismatch scan then scalar. */
	public static int compareUnsigned(byte[] a, byte[] b) {
		if (a == b) {
			return 0;
		}
		if (a == null) {
			return -1;
		}
		if (b == null) {
			return 1;
		}
		final VectorSpecies<Byte> species = VectorLane.SPECIES;
		final int min = Math.min(a.length, b.length);
		final int upper = species.loopBound(min);
		int i = 0;
		for (; i < upper; i += species.length()) {
			final ByteVector va = ByteVector.fromArray(species, a, i);
			final ByteVector vb = ByteVector.fromArray(species, b, i);
			final long neMask = va.eq(vb).not().toLong();
			if (neMask != 0L) {
				final int lane = Long.numberOfTrailingZeros(neMask);
				final int idx = i + lane;
				return Byte.compareUnsigned(a[idx], b[idx]);
			}
		}
		for (; i < min; i++) {
			final int c = Byte.compareUnsigned(a[i], b[i]);
			if (c != 0) {
				return c;
			}
		}
		return Integer.compare(a.length, b.length);
	}

	/**
	 * Experimental vectorized hash for JMH only — must match {@link ArrayUtil#fastHash}
	 * bit-for-bit when used; implemented as scalar fold for contract safety, with
	 * vectorized XOR checksum path available via {@link #xorChecksum(byte[])}.
	 */
	public static int fastHashCompatible(byte[] array) {
		return ArrayUtil.fastHash(array);
	}

	/** Vectorized XOR checksum (not a drop-in for fastHash). */
	public static int xorChecksum(byte[] array) {
		if (array == null || array.length == 0) {
			return 0;
		}
		final VectorSpecies<Byte> species = VectorLane.SPECIES;
		final int length = array.length;
		final int upper = species.loopBound(length);
		ByteVector acc = ByteVector.broadcast(species, (byte) 0);
		int i = 0;
		for (; i < upper; i += species.length()) {
			acc = acc.lanewise(VectorOperators.XOR, ByteVector.fromArray(species, array, i));
		}
		int result = 0;
		final byte[] lane = acc.toArray();
		for (byte b : lane) {
			result ^= (b & 0xff);
		}
		for (; i < length; i++) {
			result ^= (array[i] & 0xff);
		}
		return result;
	}

	/**
	 * CRC-32 of the full array, bit-compatible with {@link CRC32#getValue()} cast to {@code int}.
	 * <p>
	 * Uses {@link CRC32} for correctness; empty / null → {@code 0}.
	 */
	public static int crc32(byte[] data) {
		if (data == null || data.length == 0) {
			return CRC32_EMPTY_VALUE;
		}
		final CRC32 crc = new CRC32();
		crc.update(data);
		return (int) crc.getValue();
	}

	/**
	 * Single call-site hygiene for streaming CRC over a full {@code byte[]} chunk.
	 * <p>
	 * No-op when {@code crc} or {@code data} is null; empty arrays leave the CRC unchanged.
	 */
	public static void updateCrc32(CRC32 crc, byte[] data) {
		if (crc == null || data == null || data.length == 0) {
			return;
		}
		crc.update(data);
	}
}
