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

import java.util.List;
import java.util.concurrent.ConcurrentMap;

import org.junit.jupiter.api.Test;

import org.genfork.grid.sql.SqlRouteClassifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared route util used by reactive RoutingConnection and Sync routing.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
class SqlClientRouteUtilTest {
	@Test
	void selectIsRead() {
		assertEquals(SqlRouteClassifier.Route.READ,
				SqlClientRouteUtil.routeFor(null, "SELECT id FROM t"));
	}

	@Test
	void insertIsWrite() {
		assertEquals(SqlRouteClassifier.Route.WRITE,
				SqlClientRouteUtil.routeFor(null, "INSERT INTO t (id) VALUES (1)"));
	}

	@Test
	void allReadBatch() {
		assertTrue(SqlClientRouteUtil.allRead(null, List.of(
				"SELECT id FROM t",
				"EXPLAIN SELECT id FROM t")));
		assertFalse(SqlClientRouteUtil.allRead(null, List.of(
				"SELECT id FROM t",
				"INSERT INTO t (id) VALUES (1)")));
	}

	@Test
	void prepareCacheAndExecute() {
		final ConcurrentMap<String, SqlRouteClassifier.Route> cache =
				SqlClientRouteUtil.newPrepareRouteCache();
		SqlClientRouteUtil.cachePrepareRoute(cache, "q", "SELECT id FROM t");
		assertEquals(SqlRouteClassifier.Route.READ, cache.get("q"));
		assertEquals(SqlRouteClassifier.Route.READ,
				SqlClientRouteUtil.routeFor(cache, "EXECUTE q"));
		SqlClientRouteUtil.onSql(cache, "DEALLOCATE q");
		assertFalse(cache.containsKey("q"));
	}
}
