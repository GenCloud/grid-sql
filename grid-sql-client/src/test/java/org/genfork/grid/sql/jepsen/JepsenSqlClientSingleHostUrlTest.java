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
package org.genfork.grid.sql.jepsen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.genfork.grid.sql.client.sync.SyncSession;
import org.junit.jupiter.api.Test;

/**
 * Characterization: single-host grid URL detection (skip useless rediscoverWriter).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class JepsenSqlClientSingleHostUrlTest {

	@Test
	void detectsSingleHostAuthority() {
		assertTrue(JepsenSqlClient.isSingleHostGridUrl(
				"grid://grid:grid@a1:15432/public?maxConnections=1"));
		assertTrue(SyncSession.isSingleHostUrl(
				"grid://127.0.0.1:15432/public"));
		assertTrue(JepsenSqlClient.isSingleHostGridUrl(null));
		assertTrue(JepsenSqlClient.isSingleHostGridUrl(""));
	}

	@Test
	void detectsMultiHostAuthority() {
		assertFalse(JepsenSqlClient.isSingleHostGridUrl(
				"grid://grid:grid@a1:15432,a2:15433,a3:15434/public"));
	}
}