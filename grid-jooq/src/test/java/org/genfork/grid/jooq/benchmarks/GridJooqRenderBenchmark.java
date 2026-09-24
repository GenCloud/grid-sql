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
package org.genfork.grid.jooq.benchmarks;

import java.util.concurrent.TimeUnit;

import org.genfork.grid.jooq.GridDSL;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Cold/warm render SELECT thrpt: GridDSL vs raw string (opt-in {@code -Pjmh}).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class GridJooqRenderBenchmark {
	private static final String RAW_SELECT =
			"SELECT users.id, users.name FROM users JOIN orders ON users.id = orders.user_id WHERE users.id = 1";
	private static final String TABLE_USERS = "users";
	private static final String TABLE_ORDERS = "orders";
	private static final String COL_ID = "id";
	private static final String COL_NAME = "name";
	private static final String COL_USER_ID = "user_id";
	private static final int LITERAL_ID = 1;

	private DSLContext dsl;
	private Table<Record> users;
	private Table<Record> orders;
	private Field<Integer> usersId;
	private Field<Integer> ordersUserId;
	private Field<String> usersName;

	@Setup
	public void setup() {
		dsl = GridDSL.using();
		users = DSL.table(DSL.name(TABLE_USERS));
		orders = DSL.table(DSL.name(TABLE_ORDERS));
		usersId = DSL.field(DSL.name(TABLE_USERS, COL_ID), Integer.class);
		ordersUserId = DSL.field(DSL.name(TABLE_ORDERS, COL_USER_ID), Integer.class);
		usersName = DSL.field(DSL.name(TABLE_USERS, COL_NAME), String.class);
	}

	@Benchmark
	public void renderSelectGridDsl(Blackhole blackhole) {
		final Query query = dsl
				.select(usersId, usersName)
				.from(users)
				.join(orders).on(usersId.eq(ordersUserId))
				.where(usersId.eq(DSL.inline(LITERAL_ID)));
		blackhole.consume(query.getSQL(ParamType.INLINED));
	}

	@Benchmark
	public void renderSelectRawString(Blackhole blackhole) {
		blackhole.consume(RAW_SELECT);
	}
}
