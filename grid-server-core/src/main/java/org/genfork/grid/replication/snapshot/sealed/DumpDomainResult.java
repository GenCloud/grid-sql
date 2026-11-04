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
package org.genfork.grid.replication.snapshot.sealed;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of a sealed domain dump: rows written plus per-shard defer reasons.
 * <p>
 * {@code rows == 0} with non-empty {@link #deferredShards()} is not success — callers
 * must not treat that as a healed seal.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public record DumpDomainResult(
		int rows,
		List<Integer> deferredShards,
		List<SealedDumpSafetyUtil.DeferReason> deferReasons
) {
	private static final DumpDomainResult EMPTY =
			new DumpDomainResult(0, List.of(), List.of());

	public DumpDomainResult {
		deferredShards = List.copyOf(Objects.requireNonNullElse(deferredShards, List.of()));
		deferReasons = List.copyOf(Objects.requireNonNullElse(deferReasons, List.of()));
	}

	public static DumpDomainResult empty() {
		return EMPTY;
	}

	public boolean anyDeferred() {
		return !deferredShards.isEmpty();
	}

	/**
	 * {@code true} when every attempted shard was deferred (no dump progress).
	 */
	public boolean fullyDeferred(int attemptedShardCount) {
		return attemptedShardCount > 0
				&& rows <= 0
				&& deferredShards.size() >= attemptedShardCount;
	}
}
