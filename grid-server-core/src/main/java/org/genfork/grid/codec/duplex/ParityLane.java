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

/**
 * Complementary parity lane: per-byte bit complement of dataLane.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public final class ParityLane {
	private ParityLane() {
	}

	public static byte[] fromDataLane(byte[] dataLane) {
		final byte[] parity = new byte[dataLane.length];
		for (int i = 0; i < dataLane.length; i++) {
			parity[i] = (byte) ~dataLane[i];
		}
		return parity;
	}

	public static byte[] toDataLane(byte[] parityLane) {
		return fromDataLane(parityLane); // complement is involution
	}

	public static boolean matches(byte[] dataLane, byte[] parityLane) {
		if (dataLane.length != parityLane.length) {
			return false;
		}
		for (int i = 0; i < dataLane.length; i++) {
			if ((byte) ~dataLane[i] != parityLane[i]) {
				return false;
			}
		}
		return true;
	}
}
