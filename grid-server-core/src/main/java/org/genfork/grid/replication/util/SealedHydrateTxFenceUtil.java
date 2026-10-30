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
package org.genfork.grid.replication.util;

import org.genfork.grid.replication.apply.ReplicaApplier;

/**
 * Per-stream open-TX fence around sealed / LAZY {@code hydrateOpLogFrom}.
 * <p>
 * Live peer apply during catalog bootstrap may leave {@code openTxId} on a stream while
 * HELLO sealed packs are still queued. Sealed baseline then {@code resetApplied} and
 * replays {@code TX_BEGIN} → fail-closed interleave that kills {@code SqlServerRuntime}
 * boot. Discard staging for that {@code domain#shard} only (never global clear).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class SealedHydrateTxFenceUtil {

	private SealedHydrateTxFenceUtil() {
	}

	/**
	 * Drop open TX staging on {@code domain#shard} before OpLog-tail hydrate after a
	 * sealed / LAZY watermark reset.
	 */
	public static void beforeSealedOrLazyOpLogHydrate(
			ReplicaApplier applier,
			String domainType,
			int shard
	) {
		discardStream(applier, domainType, shard);
	}

	/**
	 * Drop incomplete TX left at the end of an OpLog-tail hydrate (map never saw partial rows).
	 */
	public static void afterOpLogHydrate(
			ReplicaApplier applier,
			String domainType,
			int shard
	) {
		discardStream(applier, domainType, shard);
	}

	private static void discardStream(
			ReplicaApplier applier,
			String domainType,
			int shard
	) {
		if (applier == null || domainType == null || domainType.isBlank()) {
			return;
		}
		applier.discardOpenTxStaging(domainType, shard);
	}
}
