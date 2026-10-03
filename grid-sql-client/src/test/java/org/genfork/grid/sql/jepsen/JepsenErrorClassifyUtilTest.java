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

import org.junit.jupiter.api.Test;

/**
 * Schema errors must be definite Jepsen :fail (not Elle :info crash).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class JepsenErrorClassifyUtilTest {

	@Test
	void unknownColumnParentIdIsSchemaError() {
		assertTrue(JepsenErrorClassifyUtil.isDefiniteSchemaError(
				"Unknown column parent_id in table jepsen_parent"));
	}

	@Test
	void unknownJoinColumnIsSchemaError() {
		assertTrue(JepsenErrorClassifyUtil.isDefiniteSchemaError(
				"unknown join column: parent_id"));
	}

	@Test
	void unknownColumnInProjectionIsSchemaError() {
		assertTrue(JepsenErrorClassifyUtil.isDefiniteSchemaError(
				"Unknown column in projection: p.id"));
	}

	@Test
	void unknownTableIsSchemaError() {
		assertTrue(JepsenErrorClassifyUtil.isDefiniteSchemaError(
				"Unknown table: jepsen_parent"));
		assertTrue(JepsenErrorClassifyUtil.isDefiniteSchemaError(
				"Unknown table for alias: jepsen_child"));
	}

	@Test
	void connectAndTimeoutAreNotSchemaErrors() {
		assertFalse(JepsenErrorClassifyUtil.isDefiniteSchemaError("connection refused"));
		assertFalse(JepsenErrorClassifyUtil.isDefiniteSchemaError("timeout"));
		assertFalse(JepsenErrorClassifyUtil.isDefiniteSchemaError(null));
		assertFalse(JepsenErrorClassifyUtil.isDefiniteSchemaError(""));
	}
}