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
package org.genfork.grid.sql.jepsen;

/**
 * Jepsen client {@code :f} ops over {@link JepsenSqlClient}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public enum JepsenOp {
	READ,
	WRITE,
	APPEND,
	TXN;

	public static JepsenOp fromToken(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("op required");
		}
		String t = raw.trim();
		if (t.startsWith(":")) {
			t = t.substring(1);
		}
		return switch (t.toLowerCase()) {
			case "read", "r" -> READ;
			case "write", "w" -> WRITE;
			case "append" -> APPEND;
			case "txn" -> TXN;
			default -> throw new IllegalArgumentException("Unknown JepsenOp: " + raw);
		};
	}

	public String token() {
		return name().toLowerCase();
	}
}
