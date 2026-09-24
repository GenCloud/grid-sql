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

import org.genfork.grid.sql.SqlRouteClassifier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.ConcurrentMap;

/**
 * Writer-backed {@link Connection} with ANTLR auto-route to READ_REPLICA when configured.
 * <p>
 * {@link #createStatement(String)} / batch: read-only SELECT/EXPLAIN → read pool; else writer.
 * {@code PREPARE} caches body route; {@code EXECUTE name} reuses that route.
 * Explicit {@link #createReadStatement} / {@link #executeRead} remain for forced replica reads.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class RoutingConnection implements Connection {
	private final Connection writer;
	private final RemoteConnectionFactory readFactory;
	private final ConcurrentMap<String, SqlRouteClassifier.Route> prepareRouteCache =
			SqlClientRouteUtil.newPrepareRouteCache();

	RoutingConnection(Connection writer, RemoteConnectionFactory readFactory) {
		this.writer = writer;
		this.readFactory = readFactory;
	}

	@Override
	public Mono<TxContext> begin() {
		return writer.begin();
	}

	@Override
	public ServerMeta serverMeta() {
		return writer.serverMeta();
	}

	@Override
	public Mono<PreparedHandle> prepare(String name, String bodySql) {
		SqlClientRouteUtil.cachePrepareRoute(prepareRouteCache, name, bodySql);
		return writer.prepare(name, bodySql);
	}

	@Override
	public Mono<Void> deallocate(String name) {
		if (name != null && !name.isBlank()) {
			prepareRouteCache.remove(name.trim());
		}
		return writer.deallocate(name);
	}

	@Override
	public Mono<Void> pin(String table, Object key) {
		return writer.pin(table, key);
	}

	@Override
	public Mono<Void> pin(String table, Object key, long ttlMs) {
		return writer.pin(table, key, ttlMs);
	}

	@Override
	public Mono<Void> pin(String table, Object key, long ttlMs, String qos) {
		return writer.pin(table, key, ttlMs, qos);
	}

	@Override
	public Mono<Void> unpin(String table, Object key) {
		return writer.unpin(table, key);
	}

	@Override
	public Mono<Void> setSchema(String schema) {
		return writer.setSchema(schema);
	}

	@Override
	public Mono<Void> setRemoteDirty(boolean enabled) {
		return writer.setRemoteDirty(enabled);
	}

	@Override
	public Mono<Void> setTimezone(String zoneId) {
		return writer.setTimezone(zoneId);
	}

	@Override
	public Statement createStatement(String sql) {
		SqlClientRouteUtil.onSql(prepareRouteCache, sql);
		if (readFactory != null
				&& SqlClientRouteUtil.routeFor(prepareRouteCache, sql) == SqlRouteClassifier.Route.READ) {
			return new RoutingReadStatement(readFactory, sql);
		}
		return writer.createStatement(sql);
	}

	/**
	 * Autocommit read statement on the READ_REPLICA pool (forced; ignores auto-route).
	 */
	public Statement createReadStatement(String sql) {
		RoutingConnectionFactory.requireReadPool(readFactory);
		return new RoutingReadStatement(readFactory, sql);
	}

	/**
	 * Execute one autocommit read on the READ_REPLICA pool.
	 */
	public Flux<Result> executeRead(String sql) {
		return createReadStatement(sql).execute();
	}

	/**
	 * Remember prepared-body route for {@code EXECUTE name} (ANTLR on PREPARE body only).
	 */
	public void cachePrepareRoute(String prepareName, String preparedBodySql) {
		SqlClientRouteUtil.cachePrepareRoute(prepareRouteCache, prepareName, preparedBodySql);
	}

	/** Test hook: cached prepare routes. */
	ConcurrentMap<String, SqlRouteClassifier.Route> prepareRouteCache() {
		return prepareRouteCache;
	}

	@Override
	public Flux<Result> executeBatch(List<String> sqls) {
		if (readFactory == null || sqls == null || sqls.isEmpty()) {
			return writer.executeBatch(sqls);
		}
		if (!SqlClientRouteUtil.allRead(prepareRouteCache, sqls)) {
			return writer.executeBatch(sqls);
		}
		return Flux.fromIterable(sqls).concatMap(sql -> createReadStatement(sql).execute());
	}

	@Override
	public Mono<Void> close() {
		prepareRouteCache.clear();
		return writer.close();
	}
}
