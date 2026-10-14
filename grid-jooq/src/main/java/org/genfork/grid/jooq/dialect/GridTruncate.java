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
package org.genfork.grid.jooq.dialect;

import java.util.Objects;

import org.jooq.Context;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Table;
import org.jooq.impl.CustomQueryPart;

/**
 * Grid-owned {@code TRUNCATE TABLE} QueryPart (SimplifiedSql; never {@code DELETE FROM}).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public final class GridTruncate extends CustomQueryPart {
	private static final String KW_TRUNCATE_TABLE = "TRUNCATE TABLE ";
	private static final String ERR_TABLE = "table";

	private final Table<?> table;

	public GridTruncate(Table<?> table) {
		this.table = Objects.requireNonNull(table, ERR_TABLE);
		final String name = table.getName();
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("table name required");
		}
	}

	@Override
	public void accept(Context<?> ctx) {
		ctx.sql(KW_TRUNCATE_TABLE);
		ctx.sql(table.getName());
	}

	public Query toQuery(DSLContext dsl) {
		Objects.requireNonNull(dsl, "dsl");
		return dsl.query("{0}", this);
	}

	public int execute(DSLContext dsl) {
		return toQuery(dsl).execute();
	}

	/** Render-only SQL for characterization. */
	public static String sql(Table<?> table) {
		Objects.requireNonNull(table, ERR_TABLE);
		final String name = table.getName();
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("table name required");
		}
		return KW_TRUNCATE_TABLE + name;
	}
}
