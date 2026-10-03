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
package index.unit.replication;

import org.genfork.grid.replication.netty.codec.PhaseRpcCodec;
import org.genfork.grid.replication.orchid.OrchidNackCode;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidNackMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Structured ORCHID_NACK wire round-trip (code + details, no free-form reason parse).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
class OrchidNackWireTest {

	@Test
	void stalePrevOpSeqRoundTrip() {
		final OrchidNackMessage msg = new OrchidNackMessage(
				"b2", 42L, OrchidNackCode.STALE_PREV_OP_SEQ, 21L, 0L, "");
		final OrchidNackMessage decoded = PhaseRpcCodec.decodeOrchidNack(PhaseRpcCodec.encodeOrchidNack(msg));
		assertEquals(msg, decoded);
		assertEquals("prevOpSeq mismatch expected=21 got=0", decoded.detailMessage());
	}

	@Test
	void notPhaseRankedKeepsActiveId() {
		final OrchidNackMessage msg = new OrchidNackMessage(
				"a2", 7L, OrchidNackCode.NOT_PHASE_RANKED, 0L, 0L, "a1");
		final OrchidNackMessage decoded = PhaseRpcCodec.decodeOrchidNack(PhaseRpcCodec.encodeOrchidNack(msg));
		assertEquals(OrchidNackCode.NOT_PHASE_RANKED, decoded.code());
		assertEquals("not phase-ranked proposer; active=a1", decoded.detailMessage());
	}
}