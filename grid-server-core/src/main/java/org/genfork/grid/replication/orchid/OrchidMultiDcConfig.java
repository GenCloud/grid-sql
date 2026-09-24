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
package org.genfork.grid.replication.orchid;

import java.util.Collection;
import java.util.Set;

/**
 * Hierarchical multi-DC voting knobs for {@link OrchidNode}.
 * <p>
 * Default: Kuramoto R + digest quorum are <strong>local DC only</strong>; remote voters contribute
 * matching digests before commit (WAN timeout). Opt-in {@code phaseCoupling} folds remotes into R.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public record OrchidMultiDcConfig(
		Set<String> remoteVoterIds,
		boolean phaseCoupling,
		long remoteVoterTimeoutMs
) {
	public static final OrchidMultiDcConfig NONE = new OrchidMultiDcConfig(Set.of(), false, 5_000L);

	public OrchidMultiDcConfig {
		remoteVoterIds = remoteVoterIds == null || remoteVoterIds.isEmpty()
				? Set.of()
				: Set.copyOf(remoteVoterIds);
		remoteVoterTimeoutMs = Math.max(1L, remoteVoterTimeoutMs);
	}

	public static OrchidMultiDcConfig of(Collection<String> remoteVoters,
	                                     boolean phaseCoupling,
	                                     long remoteVoterTimeoutMs) {
		return new OrchidMultiDcConfig(
				remoteVoters == null ? Set.of() : Set.copyOf(remoteVoters),
				phaseCoupling,
				remoteVoterTimeoutMs
		);
	}

	public boolean hasRemoteVoters() {
		return !remoteVoterIds.isEmpty();
	}
}
