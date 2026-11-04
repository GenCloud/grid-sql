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
 * Detect AUTH / obtain failures that mean no complete-data replica is available so auto-route
 * can fall back to the writer instead of returning a silent empty / stale SELECT.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class ReplicaReadFallbackUtil {
	private ReplicaReadFallbackUtil() {
	}

	/**
	 * {@code true} when the throwable chain signals apply-lag / incomplete replica (AUTH rotate
	 * exhausted). Walks causes so SyncAwait / Reactor wrappers stay covered.
	 */
	public static boolean isIncompleteReplica(Throwable error) {
		Throwable cur = error;
		while (cur != null) {
			final String msg = cur.getMessage();
			if (msg != null && msg.contains(FactoryAuthRedirectUtil.ERR_APPLY_LAG_STALE)) {
				return true;
			}
			cur = cur.getCause();
		}
		return false;
	}
}
