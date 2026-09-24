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
package org.genfork.grid.common;

/**
 * Shared message / type markers for orchid / region fence rejects that require
 * writer rediscovery (and optional one transparent retry).
 * <p>
 * JDK-only — usable from {@code grid-sql-client} and {@code grid-server-core}
 * without coupling those modules to each other.
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public final class WriterFenceSignals {

	public static final String ORCHID_NOT_SYNCED = "OrchidNotSynced";
	public static final String PHASE_RANKED = "phase-ranked";
	public static final String WRITER_ELIGIBLE = "writerEligible";
	public static final String WRITE_REQUIRES_PHASE_RANKED = "write requires phase-ranked";
	public static final String READ_REQUIRES_PHASE_RANKED = "read requires phase-ranked";
	public static final String WRITE_DENIED_LEARNER = "write denied on cross-dc learner";
	public static final String STALE_REPLICA_READ = "stale replica read";
	public static final String READ_DENIED_LEARNER = "read denied on cross-dc learner";
	public static final String WRITE_DENIED_APPLY_LAG = "write denied until apply lag";
	public static final String REGION_FENCED = "region fenced";

	private WriterFenceSignals() {
	}

	/**
	 * True when the throwable indicates a writer fence / orchid reject that should
	 * trigger rediscover (and at most one product retry).
	 */
	public static boolean requiresWriterRediscover(Throwable ex) {
		if (ex == null) {
			return false;
		}
		final Throwable root = rootCause(ex);
		final String name = root.getClass().getSimpleName();
		final String msg = root.getMessage() == null ? "" : root.getMessage();
		return name.contains(ORCHID_NOT_SYNCED)
				|| msg.contains(ORCHID_NOT_SYNCED)
				|| msg.contains(PHASE_RANKED)
				|| msg.contains(WRITER_ELIGIBLE)
				|| msg.contains(WRITE_REQUIRES_PHASE_RANKED)
				|| msg.contains(READ_REQUIRES_PHASE_RANKED)
				|| msg.contains(WRITE_DENIED_LEARNER)
				|| msg.contains(STALE_REPLICA_READ)
				|| msg.contains(READ_DENIED_LEARNER)
				|| msg.contains(WRITE_DENIED_APPLY_LAG)
				|| msg.contains(REGION_FENCED);
	}

	private static Throwable rootCause(Throwable ex) {
		Throwable cur = ex;
		while (cur.getCause() != null && cur.getCause() != cur) {
			cur = cur.getCause();
		}
		return cur;
	}
}