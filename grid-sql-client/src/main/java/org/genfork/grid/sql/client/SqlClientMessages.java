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
 * Shared client-facing state / admission error messages (named constants only).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class SqlClientMessages {
	public static final String TX_CLOSED = "TxContext closed";
	public static final String FACTORY_DISPOSED = "connection factory disposed";
	public static final String ENDPOINTS_EMPTY = "endpoints must not be empty";
	public static final String ALL_ENDPOINTS_FAILED = "all endpoints failed";
	public static final String SAVEPOINT_REQUIRED = "savepoint required";

	private SqlClientMessages() {
	}

	public static String maxConnectionsExhausted(int maxConnections) {
		return "maxConnections=" + maxConnections + " exhausted";
	}
}
