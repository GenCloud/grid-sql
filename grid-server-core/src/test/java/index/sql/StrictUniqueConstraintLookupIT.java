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
package index.sql;

import org.genfork.grid.exceptions.NonUniqueValueException;
import org.genfork.grid.serial.PrimaryKeyCodec;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * IT-R4: {@code validateConstraints} STRICT secondary via wire {@code lookupEqKeys}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class StrictUniqueConstraintLookupIT {
	private static final int SHARDS = 4;

	@Test
	void validateConstraintsRejectsSecondIdentityOnSameUnique() {
		final SqlEngine engine = SqlBenchHelper.createEngine(SHARDS);
		engine.execute(
				"CREATE TABLE t ("
						+ "id INT PRIMARY KEY, "
						+ "email VARCHAR NOT NULL, "
						+ "v VARCHAR)");
		engine.execute("CREATE UNIQUE INDEX t_email_u ON t (email)");
		engine.execute("INSERT INTO t (id, email, v) VALUES (1, 'a@b.c', 'x')");

		final TableStore store = SqlBenchHelper.store(engine, "t");
		final Object[] coerced = new Object[]{2, "a@b.c", "y"};
		final byte[] keyBytes = PrimaryKeyCodec.encodeArgument(store.schema(), 2);
		assertThrows(NonUniqueValueException.class,
				() -> store.validateConstraints(coerced, keyBytes, false));
	}
}
