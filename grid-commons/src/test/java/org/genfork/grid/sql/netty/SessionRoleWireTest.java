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
package org.genfork.grid.sql.netty;

import org.genfork.grid.sql.client.SessionRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SESSION_OPEN v2 role encode/decode.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public class SessionRoleWireTest {
	@Test
	void primaryPublicEmptyLegacy() {
		final byte[] payload = SessionRoleWire.encode(SessionRole.PRIMARY, "public");
		assertEquals(0, payload.length);
		final SessionRoleWire.SessionOpen open = SessionRoleWire.decode(payload);
		assertEquals(SessionRole.PRIMARY, open.role());
		assertEquals("public", open.schema());
	}

	@Test
	void readReplicaRoundTrip() {
		final byte[] payload = SessionRoleWire.encode(SessionRole.READ_REPLICA, "public");
		final SessionRoleWire.SessionOpen open = SessionRoleWire.decode(payload);
		assertEquals(SessionRole.READ_REPLICA, open.role());
		assertEquals("public", open.schema());
	}

	@Test
	void timezoneRoundTrip() {
		final byte[] payload = SessionRoleWire.encode(SessionRole.PRIMARY, "public", "Europe/Moscow");
		final SessionRoleWire.SessionOpen open = SessionRoleWire.decode(payload);
		assertEquals(SessionRole.PRIMARY, open.role());
		assertEquals("public", open.schema());
		assertEquals("Europe/Moscow", open.timezoneOrNull());
	}
}