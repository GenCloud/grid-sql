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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import org.genfork.grid.sql.client.transport.PendingExchange;
import org.genfork.grid.sql.client.transport.SqlClientInboundHandler;
import org.genfork.grid.sql.netty.SqlFrame;
import org.genfork.grid.sql.netty.SqlOpcode;
import org.genfork.grid.sql.netty.SqlWire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip for AUTH_OK / ERROR / PROMOTE_NOTIFY ServerMeta encoding.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
class ServerMetaWireTest {
	private static final int SERVER_PUSH_REQUEST_ID = 0;

	@Test
	void roundTripWriterEligible() {
		final ServerMeta meta = new ServerMeta("a1", true, "a1", false, 7L);
		final byte[] encoded = SqlWire.serverMeta(meta);
		final ServerMeta decoded = SqlWire.readServerMeta(encoded);
		assertEquals("a1", decoded.nodeId());
		assertTrue(decoded.writerEligible());
		assertEquals("a1", decoded.promoteHint());
		assertFalse(decoded.applyLagStale());
		assertEquals(7L, decoded.schemaEpoch());
		assertEquals(0L, decoded.regionEpoch());
		assertEquals(ServerMeta.REGION_ROLE_NONE, decoded.regionRole());
		assertTrue(decoded.hasPromoteHint());
	}

	@Test
	void promoteNotifyReusesServerMetaPayload() {
		final ServerMeta meta = new ServerMeta("n1", false, "n2", false, 9L);
		assertEquals(meta, SqlWire.readServerMeta(SqlWire.serverMeta(meta)));
	}

	@Test
	void promoteNotifyUpdatesClientMetadataWithoutPendingExchange() {
		final Map<Integer, PendingExchange> pending = new ConcurrentHashMap<>();
		final AtomicReference<ServerMeta> received = new AtomicReference<>(ServerMeta.EMPTY);
		final SqlClientInboundHandler handler = new SqlClientInboundHandler(pending, received::set);
		final EmbeddedChannel channel = new EmbeddedChannel(handler);
		final ServerMeta meta = new ServerMeta("n2", true, "n2", false, 11L);
		try {
			channel.writeInbound(new SqlFrame(
					SqlOpcode.PROMOTE_NOTIFY,
					SERVER_PUSH_REQUEST_ID,
					SqlWire.serverMeta(meta)
			));
			assertEquals(meta, received.get());
			assertTrue(pending.isEmpty());
		} finally {
			channel.finishAndReleaseAll();
		}
	}

	@Test
	void emptyPayloadIsEmptyMeta() {
		assertEquals(ServerMeta.EMPTY, SqlWire.readServerMeta(new byte[0]));
		assertEquals(ServerMeta.EMPTY, SqlWire.readServerMeta(null));
	}

	@Test
	void errorCarriesMetaTail() {
		final ServerMeta meta = new ServerMeta("a2", false, "a1", true, 3L);
		final byte[] payload = SqlWire.error(42, "write requires phase-ranked proposer", meta);
		final SqlWire.ErrorPayload err = SqlWire.readError(payload);
		assertEquals(42, err.code());
		assertTrue(err.hasServerMeta());
		assertEquals("a1", err.serverMeta().promoteHint());
		assertTrue(err.serverMeta().applyLagStale());
	}

	@Test
	void roundTripRegionEpoch() {
		final ServerMeta meta = new ServerMeta("b1", true, "b1", false, 2L, 5L, ServerMeta.REGION_ROLE_ACTIVE);
		final ServerMeta decoded = SqlWire.readServerMeta(SqlWire.serverMeta(meta));
		assertEquals(5L, decoded.regionEpoch());
		assertEquals(ServerMeta.REGION_ROLE_ACTIVE, decoded.regionRole());
		assertTrue(decoded.hasRegionEpoch());
	}
}