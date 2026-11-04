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
package index.unit.sql;

import java.util.List;

import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.sql.exec.SqlPkLookupUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlPkLookupUtil} scalar / JOIN PK point-probe gates.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class SqlPkLookupUtilTest {
	@Test
	void scalarPkPointLookupTrueForSolePkColumn() {
		final TableSchema schema = TableSchema.builder("scalar_pk")
				.primaryKey("id", SqlType.INT)
				.column("v", SqlType.INT)
				.build();
		assertTrue(SqlPkLookupUtil.isScalarPkPointLookup(schema, "id"));
		assertTrue(SqlPkLookupUtil.isJoinPkPointProbe(schema, List.of("id")));
		assertFalse(SqlPkLookupUtil.isJoinPkPointProbe(schema, List.of("v")));
	}

	@Test
	void compositePkRejectsPartialJoinPkProbe() {
		final TableSchema schema = TableSchema.builder("cmp_pk")
				.primaryKey("p1", SqlType.INT)
				.primaryKey("p2", SqlType.INT)
				.column("v", SqlType.INT)
				.build();
		assertFalse(SqlPkLookupUtil.isScalarPkPointLookup(schema, "p1"));
		assertFalse(SqlPkLookupUtil.isJoinPkPointProbe(schema, List.of("p1")));
		assertFalse(SqlPkLookupUtil.isJoinPkPointProbe(schema, List.of("p1", "p2")));
	}

	@Test
	void joinPkProbeRequiresSingleOnColumn() {
		final TableSchema schema = TableSchema.builder("scalar_pk2")
				.primaryKey("id", SqlType.INT)
				.column("v", SqlType.INT)
				.build();
		assertFalse(SqlPkLookupUtil.isJoinPkPointProbe(schema, List.of()));
		assertFalse(SqlPkLookupUtil.isJoinPkPointProbe(schema, null));
		assertFalse(SqlPkLookupUtil.isJoinPkPointProbe(schema, List.of("id", "v")));
	}
}
