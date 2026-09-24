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

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.genfork.grid.sql.client.Connection;
import org.genfork.grid.sql.client.Result;
import org.genfork.grid.sql.client.Row;
import org.genfork.grid.sql.client.Statement;
import org.genfork.grid.sql.client.TxContext;

/**
 * Reactive SQL helpers over {@link TxContext} / {@link Connection}.
 * <p>
 * Does not own TX lifecycle, never calls {@code .block()}, and does not use SyncAwait.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class ReactiveSqlOps {
	private static final String ERR_TX_REQUIRED = "txContext required";
	private static final String ERR_CONNECTION_REQUIRED = "connection required";
	private static final String ERR_SQL_REQUIRED = "sql required";
	private static final String ERR_MAPPER_REQUIRED = "rowMapper required";
	private static final String ERR_STATEMENT_REQUIRED = "statement required";
	private static final String ERR_STEPS_REQUIRED = "steps required";
	private static final String ERR_SQLS_REQUIRED = "sqls required";

	private ReactiveSqlOps() {
	}

	public static Statement statement(TxContext tx, String sql, Object... binds) {
		Objects.requireNonNull(tx, ERR_TX_REQUIRED);
		requireSql(sql);
		return SqlBind.bindAll(tx.createStatement(sql), binds);
	}

	public static Statement statement(Connection conn, String sql, Object... binds) {
		Objects.requireNonNull(conn, ERR_CONNECTION_REQUIRED);
		requireSql(sql);
		return SqlBind.bindAll(conn.createStatement(sql), binds);
	}

	public static Statement bindAll(Statement st, Object... binds) {
		Objects.requireNonNull(st, ERR_STATEMENT_REQUIRED);
		return SqlBind.bindAll(st, binds);
	}

	public static Mono<Long> executeUpdate(TxContext tx, String sql, Object... binds) {
		return statement(tx, sql, binds).executeUpdate();
	}

	public static Mono<Long> executeUpdate(Connection conn, String sql, Object... binds) {
		return statement(conn, sql, binds).executeUpdate();
	}

	public static Mono<Row> fetchOne(TxContext tx, String sql, Object... binds) {
		return statement(tx, sql, binds).fetchOne();
	}

	public static Mono<Row> fetchOne(Connection conn, String sql, Object... binds) {
		return statement(conn, sql, binds).fetchOne();
	}

	public static <T> Mono<T> fetchOne(TxContext tx, String sql, RowMapper<T> mapper, Object... binds) {
		Objects.requireNonNull(mapper, ERR_MAPPER_REQUIRED);
		return fetchOne(tx, sql, binds).map(mapper::map);
	}

	public static <T> Mono<T> fetchOne(Connection conn, String sql, RowMapper<T> mapper, Object... binds) {
		Objects.requireNonNull(mapper, ERR_MAPPER_REQUIRED);
		return fetchOne(conn, sql, binds).map(mapper::map);
	}

	public static <T> Mono<Optional<T>> fetchOptional(
			TxContext tx,
			String sql,
			RowMapper<T> mapper,
			Object... binds
	) {
		Objects.requireNonNull(mapper, ERR_MAPPER_REQUIRED);
		return fetchOne(tx, sql, binds)
				.map(row -> Optional.of(mapper.map(row)))
				.defaultIfEmpty(Optional.empty());
	}

	public static <T> Mono<Optional<T>> fetchOptional(
			Connection conn,
			String sql,
			RowMapper<T> mapper,
			Object... binds
	) {
		Objects.requireNonNull(mapper, ERR_MAPPER_REQUIRED);
		return fetchOne(conn, sql, binds)
				.map(row -> Optional.of(mapper.map(row)))
				.defaultIfEmpty(Optional.empty());
	}

	public static Flux<Row> fetchMany(TxContext tx, String sql, Object... binds) {
		return statement(tx, sql, binds)
				.execute()
				.filter(Result::isResultSet)
				.concatMap(result -> result.map((row, meta) -> row));
	}

	public static Flux<Row> fetchMany(Connection conn, String sql, Object... binds) {
		return statement(conn, sql, binds)
				.execute()
				.filter(Result::isResultSet)
				.concatMap(result -> result.map((row, meta) -> row));
	}

	public static <T> Flux<T> fetchMany(TxContext tx, String sql, RowMapper<T> mapper, Object... binds) {
		Objects.requireNonNull(mapper, ERR_MAPPER_REQUIRED);
		return fetchMany(tx, sql, binds).map(mapper::map);
	}

	public static <T> Flux<T> fetchMany(Connection conn, String sql, RowMapper<T> mapper, Object... binds) {
		Objects.requireNonNull(mapper, ERR_MAPPER_REQUIRED);
		return fetchMany(conn, sql, binds).map(mapper::map);
	}

	public static <T> Mono<List<T>> fetchList(TxContext tx, String sql, RowMapper<T> mapper, Object... binds) {
		return fetchMany(tx, sql, mapper, binds).collectList();
	}

	public static <T> Mono<List<T>> fetchList(Connection conn, String sql, RowMapper<T> mapper, Object... binds) {
		return fetchMany(conn, sql, mapper, binds).collectList();
	}

	/**
	 * {@code true} when the first result-set row is present.
	 */
	public static Mono<Boolean> fetchExists(TxContext tx, String sql, Object... binds) {
		return fetchOne(tx, sql, binds).hasElement();
	}

	public static Mono<Boolean> fetchExists(Connection conn, String sql, Object... binds) {
		return fetchOne(conn, sql, binds).hasElement();
	}

	/**
	 * Same as {@link #fetchOne(TxContext, String, RowMapper, Object...)} for {@code RETURNING} SQL.
	 */
	public static <T> Mono<T> fetchReturningOne(
			TxContext tx,
			String sql,
			RowMapper<T> mapper,
			Object... binds
	) {
		return fetchOne(tx, sql, mapper, binds);
	}

	/**
	 * Same as {@link #fetchMany(TxContext, String, RowMapper, Object...)} for {@code RETURNING} SQL.
	 */
	public static <T> Flux<T> fetchReturningMany(
			TxContext tx,
			String sql,
			RowMapper<T> mapper,
			Object... binds
	) {
		return fetchMany(tx, sql, mapper, binds);
	}

	public static Flux<Result> executeBatchAutocommit(Connection conn, List<String> sqls) {
		Objects.requireNonNull(conn, ERR_CONNECTION_REQUIRED);
		Objects.requireNonNull(sqls, ERR_SQLS_REQUIRED);
		return conn.executeBatch(sqls);
	}

	/**
	 * Runs {@code steps} sequentially on {@code tx} (concatMap then), sharing the open TX.
	 */
	public static Mono<Void> executeInOrder(TxContext tx, List<BoundSql> steps) {
		Objects.requireNonNull(tx, ERR_TX_REQUIRED);
		Objects.requireNonNull(steps, ERR_STEPS_REQUIRED);
		return Flux.fromIterable(steps)
				.concatMap(step -> statement(tx, step.sql(), step.binds()).execute().then())
				.then();
	}

	/**
	 * Runs update {@code steps} sequentially; returns rows-affected per step.
	 */
	public static Mono<long[]> executeUpdatesInOrder(TxContext tx, List<BoundSql> steps) {
		Objects.requireNonNull(tx, ERR_TX_REQUIRED);
		Objects.requireNonNull(steps, ERR_STEPS_REQUIRED);
		return Flux.fromIterable(steps)
				.concatMap(step -> executeUpdate(tx, step.sql(), step.binds()))
				.collectList()
				.map(ReactiveSqlOps::toLongArray);
	}

	private static void requireSql(String sql) {
		if (sql == null || sql.isBlank()) {
			throw new IllegalArgumentException(ERR_SQL_REQUIRED);
		}
	}

	private static long[] toLongArray(List<Long> values) {
		final long[] out = new long[values.size()];
		for (int i = 0; i < values.size(); i++) {
			out[i] = values.get(i);
		}
		return out;
	}
}