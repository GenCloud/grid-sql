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
package org.genfork.grid.sql.client.ops;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BoundSql construction and defensive copies.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
class BoundSqlTest {
	@Test
	void ofCopiesBindsAndRejectsBlankSql() {
		final Object[] input = new Object[]{1, null, "x"};
		final BoundSql bound = BoundSql.of("SELECT ?", input);
		assertEquals("SELECT ?", bound.sql());
		assertArrayEquals(input, bound.binds());
		assertNotSame(input, bound.binds());
		input[0] = 99;
		assertEquals(1, bound.binds()[0]);
	}

	@Test
	void nullBindsBecomeEmpty() {
		final BoundSql bound = new BoundSql("SELECT 1", null);
		assertEquals(0, bound.binds().length);
		assertTrue(Arrays.equals(new Object[0], bound.binds()));
	}

	@Test
	void blankSqlRejected() {
		assertThrows(IllegalArgumentException.class, () -> BoundSql.of("  "));
		assertThrows(IllegalArgumentException.class, () -> BoundSql.of(null));
	}
}