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

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lock-free bit set backed by {@link AtomicLongArray} + CAS.
 * <p>
 * Word array grows via {@link AtomicReference} CAS — no monitor held across SQL / Netty EL.
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public final class AtomicBitSet {
	private static final int BITS_PER_WORD = 64;
	private static final int WORD_SHIFT = 6;
	private static final long WORD_MASK = BITS_PER_WORD - 1L;
	private static final int INITIAL_WORDS = 1;
	private static final int BYTE_MASK = 0xff;
	private static final int BYTES_PER_WORD = BITS_PER_WORD / 8;

	private final AtomicReference<AtomicLongArray> wordsRef;

	public AtomicBitSet() {
		this.wordsRef = new AtomicReference<>(new AtomicLongArray(INITIAL_WORDS));
	}

	/**
	 * Construct from a packed little-endian bit mask ({@link java.util.BitSet#toByteArray} layout).
	 */
	public AtomicBitSet(byte[] packed) {
		this();
		if (packed == null || packed.length == 0) {
			return;
		}
		orPacked(packed);
	}

	/**
	 * Set bit at {@code bitIndex} (grows capacity as needed).
	 *
	 * @return {@code true} if the bit changed from clear to set
	 */
	public boolean set(int bitIndex) {
		if (bitIndex < 0) {
			return false;
		}
		ensureCapacity(bitIndex + 1);
		final int wordIndex = bitIndex >>> WORD_SHIFT;
		final long mask = 1L << (bitIndex & WORD_MASK);
		for (;;) {
			final AtomicLongArray local = wordsRef.get();
			if (wordIndex >= local.length()) {
				ensureCapacity(bitIndex + 1);
				continue;
			}
			final long prev = local.get(wordIndex);
			final long next = prev | mask;
			if (prev == next) {
				return false;
			}
			if (local.compareAndSet(wordIndex, prev, next)) {
				return true;
			}
		}
	}

	/**
	 * Clear bit at {@code bitIndex}.
	 *
	 * @return {@code true} if the bit changed from set to clear
	 */
	public boolean clear(int bitIndex) {
		if (bitIndex < 0) {
			return false;
		}
		final AtomicLongArray local = wordsRef.get();
		final int wordIndex = bitIndex >>> WORD_SHIFT;
		if (wordIndex >= local.length()) {
			return false;
		}
		final long mask = 1L << (bitIndex & WORD_MASK);
		for (;;) {
			final long prev = local.get(wordIndex);
			final long next = prev & ~mask;
			if (prev == next) {
				return false;
			}
			if (local.compareAndSet(wordIndex, prev, next)) {
				return true;
			}
		}
	}

	public boolean get(int bitIndex) {
		if (bitIndex < 0) {
			return false;
		}
		final AtomicLongArray local = wordsRef.get();
		final int wordIndex = bitIndex >>> WORD_SHIFT;
		if (wordIndex >= local.length()) {
			return false;
		}
		return (local.get(wordIndex) & (1L << (bitIndex & WORD_MASK))) != 0L;
	}

	/**
	 * Index of the next set bit at or after {@code fromIndex}, or {@code -1}.
	 */
	public int nextSetBit(int fromIndex) {
		if (fromIndex < 0) {
			fromIndex = 0;
		}
		final AtomicLongArray local = wordsRef.get();
		int wordIndex = fromIndex >>> WORD_SHIFT;
		if (wordIndex >= local.length()) {
			return -1;
		}
		long word = local.get(wordIndex) & (-1L << (fromIndex & WORD_MASK));
		for (;;) {
			if (word != 0L) {
				return (wordIndex << WORD_SHIFT) + Long.numberOfTrailingZeros(word);
			}
			wordIndex++;
			if (wordIndex >= local.length()) {
				return -1;
			}
			word = local.get(wordIndex);
		}
	}

	/**
	 * Claim the lowest set bit (clear it) via CAS. Returns bit index or {@code -1}.
	 */
	public int claimNextSetBit() {
		for (;;) {
			final int bit = nextSetBit(0);
			if (bit < 0) {
				return -1;
			}
			if (clear(bit)) {
				return bit;
			}
		}
	}

	public void clearAll() {
		final AtomicLongArray local = wordsRef.get();
		for (int i = 0; i < local.length(); i++) {
			local.set(i, 0L);
		}
	}

	/**
	 * Replace contents from packed little-endian bytes.
	 */
	public void loadFrom(byte[] packed) {
		clearAll();
		orPacked(packed);
	}

	/**
	 * Pack bits into little-endian bytes (compatible with {@link java.util.BitSet#toByteArray}).
	 */
	public byte[] toByteArray() {
		final AtomicLongArray local = wordsRef.get();
		int lastWord = -1;
		for (int i = local.length() - 1; i >= 0; i--) {
			if (local.get(i) != 0L) {
				lastWord = i;
				break;
			}
		}
		if (lastWord < 0) {
			return new byte[0];
		}
		final int byteLen = (lastWord + 1) * BYTES_PER_WORD;
		final byte[] out = new byte[byteLen];
		for (int w = 0; w <= lastWord; w++) {
			long word = local.get(w);
			final int base = w * BYTES_PER_WORD;
			for (int b = 0; b < BYTES_PER_WORD; b++) {
				out[base + b] = (byte) (word & BYTE_MASK);
				word >>>= 8;
			}
		}
		int trim = out.length;
		while (trim > 0 && out[trim - 1] == 0) {
			trim--;
		}
		if (trim == out.length) {
			return out;
		}
		final byte[] trimmed = new byte[trim];
		System.arraycopy(out, 0, trimmed, 0, trim);
		return trimmed;
	}

	/**
	 * OR packed little-endian mask into this set ({@link java.util.BitSet#valueOf} layout).
	 */
	public void orPacked(byte[] packed) {
		if (packed == null || packed.length == 0) {
			return;
		}
		final int bitCount = packed.length * 8;
		ensureCapacity(bitCount);
		for (int bit = 0; bit < bitCount; bit++) {
			final int byteIndex = bit >>> 3;
			final int bitInByte = bit & 7;
			if (((packed[byteIndex] >>> bitInByte) & 1) != 0) {
				set(bit);
			}
		}
	}

	public int wordLength() {
		return wordsRef.get().length();
	}

	private void ensureCapacity(int bits) {
		if (bits <= 0) {
			return;
		}
		final int needWords = (bits + BITS_PER_WORD - 1) >>> WORD_SHIFT;
		for (;;) {
			final AtomicLongArray local = wordsRef.get();
			if (needWords <= local.length()) {
				return;
			}
			final AtomicLongArray grown = new AtomicLongArray(needWords);
			for (int i = 0; i < local.length(); i++) {
				grown.set(i, local.get(i));
			}
			if (!wordsRef.compareAndSet(local, grown)) {
				continue;
			}
			// Merge concurrent updates that landed on the old array after the snapshot copy.
			for (int i = 0; i < local.length(); i++) {
				final long spilled = local.get(i);
				if (spilled == 0L) {
					continue;
				}
				for (;;) {
					final long prev = grown.get(i);
					if (grown.compareAndSet(i, prev, prev | spilled)) {
						break;
					}
				}
			}
			return;
		}
	}
}