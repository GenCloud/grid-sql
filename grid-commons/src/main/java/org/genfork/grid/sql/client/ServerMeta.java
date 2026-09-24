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
 * Proposer / replication metadata carried on AUTH_OK, ERROR, and PROMOTE_NOTIFY frames.
 * <p>
 * {@code schemaEpoch} is DDL catalog epoch; {@code regionEpoch}/{@code regionRole} are Multi-DC
 * Active/Hold fencing (orthogonal — never mixed).
 *
 * Lives in {@code grid-commons} (shared client/server; JDK-only).
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public record ServerMeta(
		String nodeId,
		boolean writerEligible,
		String promoteHint,
		boolean applyLagStale,
		long schemaEpoch,
		long regionEpoch,
		byte regionRole
) {
	public static final byte REGION_ROLE_NONE = 0;
	public static final byte REGION_ROLE_ACTIVE = 1;
	public static final byte REGION_ROLE_HOLD = 2;
	public static final byte REGION_ROLE_WITNESS = 3;

	public static final ServerMeta EMPTY =
			new ServerMeta("", false, "", false, 0L, 0L, REGION_ROLE_NONE);

	/**
	 * Solo / peer-replication disabled: this node accepts writes (no HA fencing).
	 */
	public static final ServerMeta SOLO_WRITER =
			new ServerMeta("", true, "", false, 0L, 0L, REGION_ROLE_NONE);

	public ServerMeta {
		nodeId = nodeId == null ? "" : nodeId;
		promoteHint = promoteHint == null ? "" : promoteHint;
	}

	/**
	 * Compatibility ctor for callers that omit region fields (region disabled / legacy).
	 */
	public ServerMeta(
			String nodeId,
			boolean writerEligible,
			String promoteHint,
			boolean applyLagStale,
			long schemaEpoch
	) {
		this(nodeId, writerEligible, promoteHint, applyLagStale, schemaEpoch, 0L, REGION_ROLE_NONE);
	}

	public boolean hasPromoteHint() {
		return !promoteHint.isBlank();
	}

	/** Prefer promoteHint when set; otherwise empty. */
	public String phaseRankedProposer() {
		return promoteHint;
	}

	public boolean hasRegionEpoch() {
		return regionEpoch > 0L;
	}
}