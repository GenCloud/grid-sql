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

/**
 * SAVEPOINT SQL rendering for {@link TxContext} API.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
class SqlSavepointSqlTest {
	@Test
	void rendersControlSql() {
		assertEquals("SAVEPOINT after_first", SqlSavepointSql.savepoint("after_first"));
		assertEquals("ROLLBACK TO SAVEPOINT after_first", SqlSavepointSql.rollbackTo("after_first"));
		assertEquals("RELEASE SAVEPOINT after_first", SqlSavepointSql.release("after_first"));
	}

	@Test
	void rejectsBlankAndUnsafeNames() {
		assertThrows(IllegalArgumentException.class, () -> SqlSavepointSql.savepoint(""));
		assertThrows(IllegalArgumentException.class, () -> SqlSavepointSql.savepoint("a b"));
		assertThrows(IllegalArgumentException.class, () -> SqlSavepointSql.savepoint("x;DROP"));
	}
}