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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.genfork.grid.sql.client.Connection;
import org.genfork.grid.sql.client.PreparedHandle;
import org.genfork.grid.sql.client.Result;
import org.genfork.grid.sql.client.Row;
import org.genfork.grid.sql.client.RowMetadata;
import org.genfork.grid.sql.client.Savepoint;
import org.genfork.grid.sql.client.Statement;
import org.genfork.grid.sql.client.TxContext;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ReactiveSqlOps behaviour with lightweight Statement/TxContext stubs.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
class ReactiveSqlOpsTest {
	private static final RowMapper<String> NAME_MAPPER = row -> row.get(0, String.class);

	@Test
	void bindAllBindsNullsAndIndexes() {
		final StubStatement st = new StubStatement("SELECT ?", Flux.empty());
		ReactiveSqlOps.bindAll(st, 1, null, "z");
		final List<Object> expected = new ArrayList<>();
		expected.add(1);
		expected.add(null);
		expected.add("z");
		assertEquals(expected, st.boundValues());
	}

	@Test
	void executeUpdateAndFetchViaTx() {
		final StubTxContext tx = new StubTxContext();
		tx.onSql("UPDATE t SET v = ?", new StubResult(2L));
		tx.onSql("SELECT name FROM t WHERE id = ?", new StubResult(List.of(stubRow("alice"))));

		StepVerifier.create(ReactiveSqlOps.executeUpdate(tx, "UPDATE t SET v = ?", 10))
				.expectNext(2L)
				.verifyComplete();

		StepVerifier.create(ReactiveSqlOps.fetchOne(tx, "SELECT name FROM t WHERE id = ?", NAME_MAPPER, 1))
				.expectNext("alice")
				.verifyComplete();

		StepVerifier.create(ReactiveSqlOps.fetchOptional(tx, "SELECT name FROM t WHERE id = ?", NAME_MAPPER, 1))
				.expectNext(Optional.of("alice"))
				.verifyComplete();

		StepVerifier.create(ReactiveSqlOps.fetchExists(tx, "SELECT name FROM t WHERE id = ?", 1))
				.expectNext(true)
				.verifyComplete();
	}

	@Test
	void fetchListAndReturning() {
		final StubTxContext tx = new StubTxContext();
		tx.onSql(
				"SELECT name FROM t",
				new StubResult(List.of(stubRow("a"), stubRow("b")))
		);
		tx.onSql(
				"INSERT INTO t(name) VALUES (?) RETURNING name",
				new StubResult(List.of(stubRow("c")))
		);

		StepVerifier.create(ReactiveSqlOps.fetchList(tx, "SELECT name FROM t", NAME_MAPPER))
				.expectNext(List.of("a", "b"))
				.verifyComplete();

		StepVerifier.create(ReactiveSqlOps.fetchReturningOne(
						tx, "INSERT INTO t(name) VALUES (?) RETURNING name", NAME_MAPPER, "c"))
				.expectNext("c")
				.verifyComplete();

		StepVerifier.create(ReactiveSqlOps.fetchReturningMany(
						tx, "INSERT INTO t(name) VALUES (?) RETURNING name", NAME_MAPPER, "c"))
				.expectNext("c")
				.verifyComplete();
	}

	@Test
	void executeUpdatesInOrderIsSequential() {
		final AtomicInteger concurrent = new AtomicInteger();
		final AtomicInteger maxConcurrent = new AtomicInteger();
		final StubTxContext tx = new StubTxContext() {
			@Override
			public Statement createStatement(String sql) {
				final StubStatement st = (StubStatement) super.createStatement(sql);
				return new Statement() {
					@Override
					public Statement bind(int index, Object value) {
						return st.bind(index, value);
					}

					@Override
					public Statement bind(String name, Object value) {
						return st.bind(name, value);
					}

					@Override
					public Flux<Result> execute() {
						final int now = concurrent.incrementAndGet();
						maxConcurrent.updateAndGet(prev -> Math.max(prev, now));
						return st.execute().doFinally(signal -> concurrent.decrementAndGet());
					}
				};
			}
		};
		tx.onSql("U1", new StubResult(1L));
		tx.onSql("U2", new StubResult(2L));
		tx.onSql("U3", new StubResult(3L));

		final List<BoundSql> steps = List.of(
				BoundSql.of("U1"),
				BoundSql.of("U2"),
				BoundSql.of("U3")
		);

		StepVerifier.create(ReactiveSqlOps.executeUpdatesInOrder(tx, steps))
				.assertNext(arr -> {
					assertEquals(3, arr.length);
					assertEquals(1L, arr[0]);
					assertEquals(2L, arr[1]);
					assertEquals(3L, arr[2]);
				})
				.verifyComplete();

		assertEquals(1, maxConcurrent.get());
	}

	@Test
	void executeInOrderAndBatchAutocommit() {
		final StubTxContext tx = new StubTxContext();
		tx.onSql("A", new StubResult(1L));
		tx.onSql("B", new StubResult(1L));

		StepVerifier.create(ReactiveSqlOps.executeInOrder(
						tx, List.of(BoundSql.of("A"), BoundSql.of("B"))))
				.verifyComplete();

		final StubConnection conn = new StubConnection();
		conn.batchResults = List.of(new StubResult(1L), new StubResult(2L));
		StepVerifier.create(ReactiveSqlOps.executeBatchAutocommit(conn, List.of("X", "Y")))
				.expectNextCount(2)
				.verifyComplete();
	}

	@Test
	void connectionFetchOverloads() {
		final StubConnection conn = new StubConnection();
		conn.onSql("SELECT name FROM t WHERE id = ?", new StubResult(List.of(stubRow("bob"))));
		conn.onSql("UPDATE t SET v = ?", new StubResult(4L));

		StepVerifier.create(ReactiveSqlOps.fetchOne(conn, "SELECT name FROM t WHERE id = ?", NAME_MAPPER, 9))
				.expectNext("bob")
				.verifyComplete();

		StepVerifier.create(ReactiveSqlOps.fetchList(conn, "SELECT name FROM t WHERE id = ?", NAME_MAPPER, 9))
				.expectNext(List.of("bob"))
				.verifyComplete();

		StepVerifier.create(ReactiveSqlOps.executeUpdate(conn, "UPDATE t SET v = ?", 1))
				.expectNext(4L)
				.verifyComplete();
	}

	@Test
	void fetchOptionalEmptyWhenNoRow() {
		final StubTxContext tx = new StubTxContext();
		tx.onSql("SELECT name FROM t", new StubResult(List.of()));

		StepVerifier.create(ReactiveSqlOps.fetchOptional(tx, "SELECT name FROM t", NAME_MAPPER))
				.expectNext(Optional.empty())
				.verifyComplete();

		StepVerifier.create(ReactiveSqlOps.fetchExists(tx, "SELECT name FROM t"))
				.expectNext(false)
				.verifyComplete();
	}

	private static Row stubRow(String name) {
		return new Row() {
			@Override
			public Object get(int index) {
				return name;
			}

			@Override
			public Object get(String col) {
				return name;
			}

			@Override
			@SuppressWarnings("unchecked")
			public <T> T get(int index, Class<T> type) {
				return (T) name;
			}

			@Override
			@SuppressWarnings("unchecked")
			public <T> T get(String col, Class<T> type) {
				return (T) name;
			}
		};
	}

	private static final class StubResult implements Result {
		private final long rowsUpdated;
		private final List<Row> rows;
		private final boolean resultSet;

		StubResult(long rowsUpdated) {
			this.rowsUpdated = rowsUpdated;
			this.rows = List.of();
			this.resultSet = false;
		}

		StubResult(List<Row> rows) {
			this.rowsUpdated = 0L;
			this.rows = List.copyOf(rows);
			this.resultSet = true;
		}

		@Override
		public Mono<Long> getRowsUpdated() {
			return Mono.just(rowsUpdated);
		}

		@Override
		public <T> Flux<T> map(BiFunction<Row, RowMetadata, T> mappingFunction) {
			if (!resultSet) {
				return Flux.empty();
			}
			return Flux.fromIterable(rows).map(row -> mappingFunction.apply(row, null));
		}

		@Override
		public boolean isResultSet() {
			return resultSet;
		}
	}

	private static final class StubStatement implements Statement {
		private final String sql;
		private final Flux<Result> results;
		private final List<Object> bound = new ArrayList<>();

		StubStatement(String sql, Flux<Result> results) {
			this.sql = sql;
			this.results = results;
		}

		List<Object> boundValues() {
			return new ArrayList<>(bound);
		}

		@Override
		public Statement bind(int index, Object value) {
			while (bound.size() <= index) {
				bound.add(null);
			}
			bound.set(index, value);
			return this;
		}

		@Override
		public Statement bind(String name, Object value) {
			throw new UnsupportedOperationException("named bind");
		}

		@Override
		public Flux<Result> execute() {
			return results;
		}

		String sql() {
			return sql;
		}
	}

	private static class StubTxContext implements TxContext {
		private final Map<String, Result> bySql = new HashMap<>();

		void onSql(String sql, Result result) {
			bySql.put(sql, result);
		}

		@Override
		public Statement createStatement(String sql) {
			final Result result = Objects.requireNonNull(bySql.get(sql), "unexpected sql: " + sql);
			return new StubStatement(sql, Flux.just(result));
		}

		@Override
		public Mono<Void> commit() {
			return Mono.empty();
		}

		@Override
		public Mono<Void> rollback() {
			return Mono.empty();
		}

		@Override
		public Mono<Savepoint> savepoint(String name) {
			return Mono.error(new UnsupportedOperationException());
		}

		@Override
		public Mono<Void> rollbackTo(Savepoint savepoint) {
			return Mono.empty();
		}

		@Override
		public Mono<Void> release(Savepoint savepoint) {
			return Mono.empty();
		}

		@Override
		public Mono<Void> close() {
			return Mono.empty();
		}

		@Override
		public Flux<Result> executeBatch(List<String> sqls) {
			return Flux.fromIterable(sqls).map(sql -> bySql.get(sql));
		}
	}

	private static final class StubConnection implements Connection {
		private final Map<String, Result> bySql = new HashMap<>();
		private List<Result> batchResults = List.of();

		void onSql(String sql, Result result) {
			bySql.put(sql, result);
		}

		@Override
		public Mono<TxContext> begin() {
			return Mono.error(new UnsupportedOperationException());
		}

		@Override
		public Statement createStatement(String sql) {
			final Result result = Objects.requireNonNull(bySql.get(sql), "unexpected sql: " + sql);
			return new StubStatement(sql, Flux.just(result));
		}

		@Override
		public Flux<Result> executeBatch(List<String> sqls) {
			return Flux.fromIterable(batchResults);
		}

		@Override
		public Mono<Void> close() {
			return Mono.empty();
		}

		@Override
		public Mono<PreparedHandle> prepare(String name, String bodySql) {
			return Mono.error(new UnsupportedOperationException());
		}
	}
}