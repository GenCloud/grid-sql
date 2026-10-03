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

/**
 * Wire reason codes for {@link OrchidTransport.OrchidNackMessage}.
 * <p>
 * Peer tip-behind a propose is <strong>not</strong> a NACK code: lagging peers buffer
 * for claim-time seal and never veto the proposer (Elle G1a under ASYNC learner tip=0).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public enum OrchidNackCode {
	/**
	 * Propose prevOpSeq is behind the peer tip (stale / already advanced).
	 * detailA = peer expected prev (local tip), detailB = propose prev.
	 */
	STALE_PREV_OP_SEQ((byte) 1),
	/** Propose digest does not match recomputed op digest. */
	DIGEST_MISMATCH((byte) 2),
	/**
	 * Same-DC propose from a node that is not phase-ranked.
	 * detailText carries active proposer id.
	 */
	NOT_PHASE_RANKED((byte) 3);

	private final byte wire;

	OrchidNackCode(byte wire) {
		this.wire = wire;
	}

	public byte wire() {
		return wire;
	}

	public static OrchidNackCode fromWire(byte wire) {
		return switch (wire) {
			case 1 -> STALE_PREV_OP_SEQ;
			case 2 -> DIGEST_MISMATCH;
			case 3 -> NOT_PHASE_RANKED;
			default -> throw new IllegalArgumentException("unknown OrchidNackCode wire=" + wire);
		};
	}
}