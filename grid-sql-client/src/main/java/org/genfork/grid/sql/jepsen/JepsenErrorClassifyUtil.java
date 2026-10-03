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
 * Jepsen op error classification helpers (definite fail vs indeterminate info).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class JepsenErrorClassifyUtil {
	/** Typed Jepsen {@code :error} token for definite schema / column miss. */
	public static final String ERR_SCHEMA = "schema-error";

	private static final String UNKNOWN_COLUMN = "Unknown column";
	private static final String UNKNOWN_JOIN_COLUMN = "unknown join column";
	private static final String UNKNOWN_COLUMN_IN_PROJECTION = "Unknown column in projection";
	/** Also matches {@code Unknown table for alias: …}. */
	private static final String UNKNOWN_TABLE = "Unknown table";

	private JepsenErrorClassifyUtil() {
	}

	/**
	 * True when the engine message is a definite SQL/schema miss (no mutation effect).
	 * Must map to Jepsen {@code :fail}, never {@code :info} (Elle crash semantics).
	 */
	public static boolean isDefiniteSchemaError(String message) {
		if (message == null || message.isBlank()) {
			return false;
		}
		return message.contains(UNKNOWN_COLUMN)
				|| message.contains(UNKNOWN_JOIN_COLUMN)
				|| message.contains(UNKNOWN_COLUMN_IN_PROJECTION)
				|| message.contains(UNKNOWN_TABLE);
	}
}