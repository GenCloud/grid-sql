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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * readEndpoints / readPreference URL parsing.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public class GridSqlUriReadEndpointsTest {
	@Test
	void parsesReadEndpoints() {
		final GridSqlUri u = GridSqlUri.parse(
				"grid://u:p@127.0.0.1:15432/public?readPreference=REPLICA&readEndpoints=127.0.0.1:15433,127.0.0.1:15434");
		assertEquals(ReadPreference.REPLICA, u.options().readPreference());
		assertEquals(2, u.options().readEndpoints().size());
		assertEquals(15433, u.options().readEndpoints().get(0).port());
	}

	@Test
	void replicaWithoutEndpointsFails() {
		assertThrows(IllegalArgumentException.class, () -> GridSqlUri.parse(
				"grid://u:p@127.0.0.1:15432/public?readPreference=REPLICA"));
	}

	@Test
	void createReadFactoryRequiresEndpoints() {
		assertThrows(IllegalArgumentException.class,
				() -> RemoteConnectionFactory.createReadFactory("grid://u:p@127.0.0.1:15432/public"));
	}

	@Test
	void defaultPreferencePrimary() {
		final GridSqlUri u = GridSqlUri.parse("grid://u:p@127.0.0.1:15432/public");
		assertEquals(ReadPreference.PRIMARY, u.options().readPreference());
		assertTrue(u.options().readEndpoints().isEmpty());
	}
}