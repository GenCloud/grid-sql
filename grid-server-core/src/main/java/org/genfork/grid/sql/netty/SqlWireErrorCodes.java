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
package org.genfork.grid.sql.netty;

/**
 * Stable ERROR frame codes for the custom SQL wire protocol.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlWireErrorCodes {
	public static final int UNKNOWN_OPCODE = 1;
	public static final int EXEC_FAILED = 2;
	public static final int AUTH_FAILED = 3;
	public static final int NOT_AUTHENTICATED = 4;
	public static final int MAX_TX_CONTEXTS = 5;
	public static final int UNKNOWN_SESSION = 6;
	public static final int READ_REPLICA_DML_DENIED = 7;
	public static final int REPLICA_READ_STALE = 8;
	public static final int REPLICA_READ_DISABLED = 9;
	public static final int LOCK_WAIT_TIMEOUT = 10;
	public static final int LOCK_WAIT_CANCELLED = 11;

	private SqlWireErrorCodes() {
	}
}