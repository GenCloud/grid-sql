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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlClientRouteContext} ThreadLocal Option push/pop.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
class SqlClientRouteContextTest {
	@Test
	void defaultIsNoneNoSuppress() {
		assertTrue(SqlClientRouteContext.current().isEmpty());
		assertFalse(SqlClientRouteContext.suppressReplicaReads());
	}

	@Test
	void forcePrimarySuppressesThenRestores() throws Exception {
		assertFalse(SqlClientRouteContext.suppressReplicaReads());
		try (AutoCloseable scope = SqlClientRouteContext.forcePrimary()) {
			assertTrue(SqlClientRouteContext.suppressReplicaReads());
			assertEquals(ReadPreference.PRIMARY, SqlClientRouteContext.current().get());
		}
		assertFalse(SqlClientRouteContext.suppressReplicaReads());
		assertTrue(SqlClientRouteContext.current().isEmpty());
	}

	@Test
	void nestedForcePrimaryRestoresOuter() throws Exception {
		try (AutoCloseable outer = SqlClientRouteContext.forcePrimary()) {
			assertTrue(SqlClientRouteContext.suppressReplicaReads());
			try (AutoCloseable inner = SqlClientRouteContext.forcePrimary()) {
				assertTrue(SqlClientRouteContext.suppressReplicaReads());
			}
			assertTrue(SqlClientRouteContext.suppressReplicaReads());
		}
		assertFalse(SqlClientRouteContext.suppressReplicaReads());
	}

	@Test
	void runWithPrimaryScopesRunnable() {
		assertFalse(SqlClientRouteContext.suppressReplicaReads());
		SqlClientRouteContext.runWithPrimary(() -> assertTrue(SqlClientRouteContext.suppressReplicaReads()));
		assertFalse(SqlClientRouteContext.suppressReplicaReads());
	}
}
