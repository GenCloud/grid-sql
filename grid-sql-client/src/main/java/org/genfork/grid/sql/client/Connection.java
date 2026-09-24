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

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * SQL connection transport (embedded or remote). One TCP multiplexes many {@link TxContext}.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public interface Connection {
	/**
	 * Open an independent TX context on this transport (SESSION_OPEN + BEGIN).
	 */
	Mono<TxContext> begin();

	/**
	 * Autocommit statement (ephemeral TX context for a single EXEC).
	 */
	Statement createStatement(String sql);

	/**
	 * Execute {@code sqls} in order as one batch (remote: one {@code BATCH_EXEC} RTT).
	 * <p>
	 * Autocommit path: each statement commits independently (session is not in an open TX).
	 * For a shared TX, use {@link TxContext#executeBatch(List)} after {@link #begin()}.
	 * Each emitted {@link Result} is one statement outcome (rows or rowsUpdated), fail-fast on error.
	 */
	Flux<Result> executeBatch(List<String> sqls);

	Mono<Void> close();

	/**
	 * Last AUTH / ERROR / PROMOTE {@link ServerMeta} observed on this transport (or EMPTY).
	 */
	default ServerMeta serverMeta() {
		return ServerMeta.EMPTY;
	}

	/**
	 * Autocommit convenience: first result's rows-affected (DDL/DML).
	 */
	default Mono<Long> executeUpdate(String sql) {
		return createStatement(sql).executeUpdate();
	}

	/**
	 * Server PREPARE {@code name AS body}; returns a bindable handle.
	 */
	default Mono<PreparedHandle> prepare(String name, String bodySql) {
		final String sql = SqlClientSql.prepareSql(name, bodySql);
		return createStatement(sql).execute()
				.then(Mono.fromCallable(() -> new RemotePreparedHandle(this, name)));
	}

	/**
	 * DEALLOCATE prepared statement by name.
	 */
	default Mono<Void> deallocate(String name) {
		return executeUpdate(SqlClientSql.deallocateSql(name)).then();
	}

	default Mono<Void> pin(String table, Object key) {
		return executeUpdate(SqlClientSql.pinSql(table, key, null, null)).then();
	}

	default Mono<Void> pin(String table, Object key, long ttlMs) {
		return executeUpdate(SqlClientSql.pinSql(table, key, ttlMs, null)).then();
	}

	default Mono<Void> pin(String table, Object key, long ttlMs, String qos) {
		return executeUpdate(SqlClientSql.pinSql(table, key, ttlMs, qos)).then();
	}

	default Mono<Void> unpin(String table, Object key) {
		return executeUpdate(SqlClientSql.unpinSql(table, key)).then();
	}

	default Mono<Void> setSchema(String schema) {
		return executeUpdate(SqlClientSql.setSchemaSql(schema)).then();
	}

	default Mono<Void> setRemoteDirty(boolean enabled) {
		return executeUpdate(SqlClientSql.setRemoteDirtySql(enabled)).then();
	}

	/**
	 * Session timezone for temporal coercion (IANA / ZoneId id). Applied on subsequent
	 * {@code SESSION_OPEN}; drains idle autocommit sessions so the next statement picks it up.
	 */
	default Mono<Void> setTimezone(String zoneId) {
		return Mono.error(new UnsupportedOperationException("setTimezone requires remote Connection"));
	}
}
