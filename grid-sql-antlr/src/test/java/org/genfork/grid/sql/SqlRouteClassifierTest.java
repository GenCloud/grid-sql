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
package org.genfork.grid.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ANTLR route classifier unit cases.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlRouteClassifierTest {
	@Test
	void selectIsRead() {
		assertEquals(SqlRouteClassifier.Route.READ,
				SqlRouteClassifier.classify("SELECT id FROM t WHERE id = 1"));
	}

	@Test
	void explainIsRead() {
		assertEquals(SqlRouteClassifier.Route.READ,
				SqlRouteClassifier.classify("EXPLAIN SELECT id FROM t"));
	}

	@Test
	void forUpdateIsWrite() {
		assertEquals(SqlRouteClassifier.Route.WRITE,
				SqlRouteClassifier.classify("SELECT id FROM t WHERE id = 1 FOR UPDATE"));
	}

	@Test
	void insertIsWrite() {
		assertEquals(SqlRouteClassifier.Route.WRITE,
				SqlRouteClassifier.classify("INSERT INTO t (id) VALUES (1)"));
	}

	@Test
	void beginIsWrite() {
		assertEquals(SqlRouteClassifier.Route.WRITE, SqlRouteClassifier.classify("BEGIN"));
	}

	@Test
	void blankRejected() {
		assertThrows(IllegalArgumentException.class, () -> SqlRouteClassifier.classify("  "));
	}

	@Test
	void unparseableRoutesWrite() {
		assertEquals(SqlRouteClassifier.Route.WRITE, SqlRouteClassifier.classify("NOT SQL AT ALL"));
	}

	@Test
	void bareSelectWithUdfRoutesWrite() {
		assertEquals(SqlRouteClassifier.Route.WRITE,
				SqlRouteClassifier.classify("SELECT double_v(1)"));
	}

	@Test
	void selectFromTableWithUdfStaysRead() {
		assertEquals(SqlRouteClassifier.Route.READ,
				SqlRouteClassifier.classify("SELECT double_v(v) FROM t"));
	}
}