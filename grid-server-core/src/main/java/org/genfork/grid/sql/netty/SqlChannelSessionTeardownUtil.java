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

import java.util.Map;
import java.util.Objects;

import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.threading.SerialTaskQueue;

/**
 * Abandon open SQL sessions on channel death / {@code SESSION_CLOSE}.
 * <p>
 * Releases record locks and Dist FOR UPDATE peer leases via
 * {@link SqlEngine#abandonSession(SqlSession)} so abrupt TCP drop (sleep/wake,
 * idle close, client kill) cannot leave holders in the shared lock manager.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class SqlChannelSessionTeardownUtil {
	private SqlChannelSessionTeardownUtil() {
	}

	/**
	 * Rollback open TX when present (unlock + Dist FOR UPDATE prepare finish false).
	 */
	public static void abandonSession(SqlEngine engine, SqlSession session) {
		Objects.requireNonNull(engine, "engine");
		if (session == null) {
			return;
		}
		engine.abandonSession(session);
	}

	/**
	 * Drain every session in {@code sessions} through {@link #abandonSession}, then clear maps.
	 * Safe to call more than once on empty maps.
	 */
	public static void abandonAll(
			SqlEngine engine,
			Map<Integer, SqlSession> sessions,
			Map<Integer, SerialTaskQueue> sessionQueues
	) {
		Objects.requireNonNull(engine, "engine");
		if (sessions != null) {
			for (SqlSession session : sessions.values()) {
				abandonSession(engine, session);
			}
			sessions.clear();
		}
		if (sessionQueues != null) {
			sessionQueues.clear();
		}
	}
}
