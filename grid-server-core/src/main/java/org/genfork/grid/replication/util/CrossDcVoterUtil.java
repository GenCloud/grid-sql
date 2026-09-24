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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.replication.crossdc.CrossDcMode;
import org.genfork.grid.replication.transport.ReplicationPeer;

/**
 * Resolve remote-DC sync voters for Cross-DC ORCHID multi-DC config.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class CrossDcVoterUtil {
	private CrossDcVoterUtil() {
	}

	/**
	 * Remote sync voters for {@link CrossDcMode#SYNC_VOTERS_ACROSS_DC}: explicit {@code voters}
	 * list, else all remote-DC peers except {@code learners}.
	 */
	public static Set<String> resolveRemoteVoters(
			CrossDcMode mode,
			GridConfigurationProperties.CrossDcProps crossDc,
			List<ReplicationPeer> peers,
			String localDc
	) {
		if (mode != CrossDcMode.SYNC_VOTERS_ACROSS_DC || peers == null || peers.isEmpty()) {
			return Set.of();
		}
		final Set<String> learners = crossDc.getLearners() == null || crossDc.getLearners().isEmpty()
				? Set.of()
				: Set.copyOf(crossDc.getLearners());
		final Set<String> configuredVoters = crossDc.getVoters() == null || crossDc.getVoters().isEmpty()
				? Set.of()
				: Set.copyOf(crossDc.getVoters());
		final Set<String> resolved = new HashSet<>();
		for (ReplicationPeer peer : peers) {
			if (peer.dc() == null || localDc.equals(peer.dc())) {
				continue;
			}
			if (!configuredVoters.isEmpty()) {
				if (configuredVoters.contains(peer.id())) {
					resolved.add(peer.id());
				}
			} else if (!learners.contains(peer.id())) {
				resolved.add(peer.id());
			}
		}
		return resolved;
	}
}