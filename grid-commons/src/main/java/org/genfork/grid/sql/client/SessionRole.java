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
package org.genfork.grid.sql.client;

/**
 * SQL session role on SESSION_OPEN: PRIMARY (default) or READ_REPLICA (autocommit reads).
 *
 * Lives in {@code grid-commons} (shared client/server; JDK-only).
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public enum SessionRole {
	PRIMARY((byte) 0),
	READ_REPLICA((byte) 1);

	private final byte wireCode;

	SessionRole(byte wireCode) {
		this.wireCode = wireCode;
	}

	public byte wireCode() {
		return wireCode;
	}

	public static SessionRole fromWire(byte code) {
		if (code == READ_REPLICA.wireCode) {
			return READ_REPLICA;
		}
		return PRIMARY;
	}

	public boolean isReadReplica() {
		return this == READ_REPLICA;
	}
}