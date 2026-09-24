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
package org.genfork.grid.jooq;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.Test;

/**
 * Lightweight cold/warm render thrpt smoke (no JMH APT). Full JMH: {@code -Pjmh}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class GridJooqRenderThrptTest {
	private static final String TABLE_USERS = "users";
	private static final String COL_ID = "id";
	private static final int LITERAL_ID = 1;
	private static final int WARM_ITERS = 2_000;
	private static final int MEASURE_ITERS = 5_000;
	private static final long MIN_OPS_PER_SEC = 1_000L;

	@Test
	void warmRenderSelectExceedsFloor() {
		final DSLContext dsl = GridDSL.using();
		final Table<Record> users = table(name(TABLE_USERS));
		final Field<Integer> id = field(name(COL_ID), Integer.class);

		for (int i = 0; i < WARM_ITERS; i++) {
			renderOnce(dsl, users, id);
		}

		final long startNanos = System.nanoTime();
		for (int i = 0; i < MEASURE_ITERS; i++) {
			renderOnce(dsl, users, id);
		}
		final long elapsedNanos = System.nanoTime() - startNanos;
		final double opsPerSec = MEASURE_ITERS * 1_000_000_000.0d / (double) elapsedNanos;
		assertTrue(opsPerSec >= MIN_OPS_PER_SEC,
				() -> "render thrpt too low: " + opsPerSec + " ops/s");
	}

	private static void renderOnce(DSLContext dsl, Table<Record> users, Field<Integer> id) {
		final Query query = dsl.select(id).from(users).where(id.eq(inline(LITERAL_ID)));
		query.getSQL(ParamType.INLINED);
	}
}
