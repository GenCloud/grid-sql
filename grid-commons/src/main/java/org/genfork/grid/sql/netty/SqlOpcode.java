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
 * Custom SQL wire opcodes (length-prefixed LE frames).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlOpcode {
	public static final byte AUTH = 1;
	public static final byte AUTH_OK = 2;
	public static final byte EXEC = 3;
	public static final byte ROW_DESC = 4;
	public static final byte ROW_DATA = 5;
	public static final byte EXEC_DONE = 6;
	public static final byte ERROR = 7;
	public static final byte SESSION_OPEN = 8;
	public static final byte SESSION_OPEN_OK = 9;
	public static final byte SESSION_CLOSE = 10;
	public static final byte SESSION_CLOSE_OK = 11;
	public static final byte FETCH = 12;
	public static final byte CANCEL = 13;
	/** N SQL statements / 1 RTT → N ordered {@code EXEC}-style result sequences. */
	public static final byte BATCH_EXEC = 14;
	/**
	 * Server-push: writer eligibility / promote hint changed (ServerMeta payload, same as AUTH_OK).
	 * Clients update sticky meta without an in-flight request.
	 */
	public static final byte PROMOTE_NOTIFY = 15;

	public static final int MAX_FRAME = 16 * 1024 * 1024;

	private SqlOpcode() {
	}
}
