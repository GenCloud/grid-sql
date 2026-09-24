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

/**
 * Multi-DC region role: only {@link #ACTIVE} admits writes for the write-ring.
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public enum RegionRole {
	ACTIVE((byte) 1),
	HOLD((byte) 2),
	WITNESS((byte) 3);

	private final byte wireCode;

	RegionRole(byte wireCode) {
		this.wireCode = wireCode;
	}

	public byte wireCode() {
		return wireCode;
	}

	public boolean admitsWrites() {
		return this == ACTIVE;
	}

	public boolean participatesInClaimQuorum() {
		return this == HOLD || this == WITNESS || this == ACTIVE;
	}

	public static RegionRole fromWire(byte code) {
		return switch (code) {
			case 1 -> ACTIVE;
			case 2 -> HOLD;
			case 3 -> WITNESS;
			default -> HOLD;
		};
	}

	public static RegionRole fromConfig(String raw) {
		if (raw == null || raw.isBlank()) {
			return ACTIVE;
		}
		return RegionRole.valueOf(raw.trim().toUpperCase());
	}
}