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
 * Detect AUTH / EXEC failures that mean no complete-data replica so auto-route SELECT
 * can fall back to the writer instead of silent empty / cascade timeout.
 * <p>
 * Covers obtain {@link FactoryAuthRedirectUtil#ERR_APPLY_LAG_STALE} and wire EXEC deny
 * fragments from {@code ReplicaAccessGate} ({@code stale replica}, {@code apply lag},
 * catalog object-set incomplete). Forced {@code createReadStatement} must not use this.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class ReplicaReadFallbackUtil {
	/** Wire / gate fragment: {@code stale replica read (apply lag exceeded)}. */
	public static final String MSG_STALE_REPLICA = "stale replica";
	/** Wire / gate fragment shared with AUTH rotate text. */
	public static final String MSG_APPLY_LAG = "apply lag";
	/** Catalog object-set fence on READ_REPLICA admit. */
	public static final String MSG_CATALOG_OBJECT_SET_INCOMPLETE = "catalog object-set incomplete";

	private ReplicaReadFallbackUtil() {
	}

	/**
	 * {@code true} when the throwable chain signals apply-lag / incomplete replica
	 * (AUTH rotate exhausted or EXEC deny). Walks causes so SyncAwait / Reactor wrappers
	 * stay covered.
	 */
	public static boolean isIncompleteReplica(Throwable error) {
		Throwable cur = error;
		while (cur != null) {
			if (isIncompleteReplicaMessage(cur.getMessage())) {
				return true;
			}
			cur = cur.getCause();
		}
		return false;
	}

	/**
	 * Match AUTH rotate and EXEC/wire error text (named fragments only).
	 */
	public static boolean isIncompleteReplicaMessage(String message) {
		if (message == null || message.isEmpty()) {
			return false;
		}
		return message.contains(FactoryAuthRedirectUtil.ERR_APPLY_LAG_STALE)
				|| message.contains(MSG_STALE_REPLICA)
				|| message.contains(MSG_APPLY_LAG)
				|| message.contains(MSG_CATALOG_OBJECT_SET_INCOMPLETE);
	}
}
