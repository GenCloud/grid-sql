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
package org.genfork.grid.replication.pitr;

import java.nio.file.Path;
import java.util.Objects;

import org.genfork.grid.replication.region.RegionRole;

/**
 * Multi-DC PITR restore entry that enforces the Active fence before offline replay.
 * <p>
 * Sync I/O on the calling thread — ops CLI / IT only, never Netty EL.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class PitrCoordinatedRestore {
	private PitrCoordinatedRestore() {
	}

	/**
	 * Require Active fence then delegate to {@link PitrRestoreMain#restore}.
	 *
	 * @param localRole            site role (must be {@link RegionRole#ACTIVE})
	 * @param remotePeerAlsoActive dual-writer signal from peer HELLO / meta
	 */
	public static PitrRestoreMain.RestoreResult restoreUnderActiveFence(
			RegionRole localRole,
			boolean remotePeerAlsoActive,
			Path baseDir,
			Path archiveDir,
			Path dataDir,
			String domain,
			int shard,
			long untilSeqInclusive,
			String nodeId,
			String clusterId
	) {
		Objects.requireNonNull(localRole, "localRole");
		PitrActiveFence.requireActiveFence(localRole, remotePeerAlsoActive);
		return PitrRestoreMain.restore(
				baseDir, archiveDir, dataDir, domain, shard, untilSeqInclusive, nodeId, clusterId);
	}
}
