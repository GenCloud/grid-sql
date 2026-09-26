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
package org.genfork.grid.replication.snapshot.sealed;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

import com.google.common.annotations.VisibleForTesting;

import org.genfork.grid.serial.WireLikeMatcher;

/**
 * Length-prefixed encode of composite BPTree keys ({@code byte[][]}) for sealed {@code .sbpt}.
 * <p>
 * Reuses {@link SealedBPTreeWriter} single {@code byte[]} key slots — not a second sealed engine.
 * Encoding is unsigned-short length + payload per component (big-endian), matching writer key limits.
 * Left-prefix EQ is a byte-prefix of the encoded form; mid-column partial EQ walks components.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class SealedCompositeIndexKey {
	private static final int LEN_BYTES = Short.BYTES;
	private static final int MAX_COMPONENT_BYTES = 0xffff;
	private static final int MAX_ENCODED_BYTES = 0xffff;

	private SealedCompositeIndexKey() {
	}

	/**
	 * Encode composite components into one sealed index key.
	 */
	public static byte[] encode(byte[][] components) {
		Objects.requireNonNull(components, "components");
		if (components.length == 0) {
			throw new IllegalArgumentException("composite sealed key requires at least one component");
		}
		long total = 0L;
		for (byte[] component : components) {
			Objects.requireNonNull(component, "component");
			if (component.length > MAX_COMPONENT_BYTES) {
				throw new IllegalArgumentException("composite sealed component exceeds unsigned-short limit");
			}
			total += LEN_BYTES + (long) component.length;
			if (total > MAX_ENCODED_BYTES) {
				throw new IllegalArgumentException("composite sealed key exceeds unsigned-short limit");
			}
		}
		final ByteBuffer buffer = ByteBuffer.allocate((int) total).order(ByteOrder.BIG_ENDIAN);
		for (byte[] component : components) {
			buffer.putShort((short) component.length).put(component);
		}
		return buffer.array();
	}

	/**
	 * Decode a sealed composite index key back to components (tests / diagnostics).
	 */
	public static byte[][] decode(byte[] encoded) {
		Objects.requireNonNull(encoded, "encoded");
		final ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
		int count = 0;
		int probe = 0;
		while (probe < encoded.length) {
			if (probe + LEN_BYTES > encoded.length) {
				throw new IllegalArgumentException("truncated composite sealed key");
			}
			final int length = Short.toUnsignedInt(buffer.getShort(probe));
			probe += LEN_BYTES + length;
			if (probe > encoded.length) {
				throw new IllegalArgumentException("truncated composite sealed key payload");
			}
			count++;
		}
		final byte[][] components = new byte[count][];
		buffer.position(0);
		for (int i = 0; i < count; i++) {
			final int length = Short.toUnsignedInt(buffer.getShort());
			final byte[] component = new byte[length];
			buffer.get(component);
			components[i] = component;
		}
		return components;
	}

	/**
	 * True when {@code encoded} begins with the length-prefixed encoding of {@code prefixComponents}
	 * (left EQ-prefix of a composite sealed key).
	 */
	public static boolean matchesLeftPrefix(byte[] encoded, byte[][] prefixComponents) {
		Objects.requireNonNull(encoded, "encoded");
		final byte[] prefixEncoded = encode(prefixComponents);
		return startsWithEncoded(encoded, prefixEncoded);
	}

	/**
	 * True when component at {@code componentIndex} equals {@code expected} (wire bytes).
	 */
	public static boolean matchesComponent(byte[] encoded, int componentIndex, byte[] expected) {
		Objects.requireNonNull(encoded, "encoded");
		Objects.requireNonNull(expected, "expected");
		if (componentIndex < 0) {
			return false;
		}
		final ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
		int index = 0;
		while (buffer.hasRemaining()) {
			if (buffer.remaining() < LEN_BYTES) {
				return false;
			}
			final int length = Short.toUnsignedInt(buffer.getShort());
			if (buffer.remaining() < length) {
				return false;
			}
			if (index == componentIndex) {
				if (length != expected.length) {
					return false;
				}
				for (int i = 0; i < length; i++) {
					if (buffer.get() != expected[i]) {
						return false;
					}
				}
				return true;
			}
			buffer.position(buffer.position() + length);
			index++;
		}
		return false;
	}

	/**
	 * True when any component of {@code encoded} matches the corresponding LIKE pattern
	 * component (same column index), mirroring RAM {@code GridPointerCompositeBPTree#matchesPattern}.
	 */
	public static boolean matchesLikePatterns(byte[] encoded, byte[][] patternComponents) {
		Objects.requireNonNull(encoded, "encoded");
		Objects.requireNonNull(patternComponents, "patternComponents");
		if (patternComponents.length == 0) {
			return false;
		}
		final byte[][] components = decode(encoded);
		final int length = Math.max(components.length, patternComponents.length);
		for (int i = 0; i < length; i++) {
			final byte[] byteKey = i >= components.length ? null : components[i];
			final byte[] patternKey = i >= patternComponents.length ? null : patternComponents[i];
			if (byteKey != null && patternKey != null
					&& WireLikeMatcher.matches(
					WireLikeMatcher.utf8Payload(byteKey),
					WireLikeMatcher.utf8Payload(patternKey))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * True when component at {@code componentIndex} is LIKE-matched by {@code patternWire}.
	 */
	public static boolean matchesComponentLike(byte[] encoded, int componentIndex, byte[] patternWire) {
		Objects.requireNonNull(encoded, "encoded");
		Objects.requireNonNull(patternWire, "patternWire");
		if (componentIndex < 0) {
			return false;
		}
		final byte[][] components = decode(encoded);
		if (componentIndex >= components.length) {
			return false;
		}
		return WireLikeMatcher.matches(
				WireLikeMatcher.utf8Payload(components[componentIndex]),
				WireLikeMatcher.utf8Payload(patternWire));
	}

	/**
	 * Inclusive/exclusive component range vs encoded key (unsigned byte order of component payload).
	 */
	public static boolean matchesComponentRange(
			byte[] encoded,
			int componentIndex,
			byte[] lowInclusiveOrNull,
			byte[] highInclusiveOrNull
	) {
		Objects.requireNonNull(encoded, "encoded");
		if (componentIndex < 0) {
			return false;
		}
		final byte[][] components = decode(encoded);
		if (componentIndex >= components.length) {
			return false;
		}
		final byte[] actual = components[componentIndex];
		if (lowInclusiveOrNull != null && compare(actual, lowInclusiveOrNull) < 0) {
			return false;
		}
		if (highInclusiveOrNull != null && compare(actual, highInclusiveOrNull) > 0) {
			return false;
		}
		return true;
	}

	@VisibleForTesting
	static int compare(byte[] left, byte[] right) {
		return SealedIndexKeyOrder.compare(left, right);
	}

	@VisibleForTesting
	static boolean startsWithEncoded(byte[] encoded, byte[] encodedPrefix) {
		if (encodedPrefix.length > encoded.length) {
			return false;
		}
		return Arrays.equals(encoded, 0, encodedPrefix.length, encodedPrefix, 0, encodedPrefix.length);
	}
}
