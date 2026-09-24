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

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.genfork.grid.sql.SqlRouteClassifier;

/**
 * Shared ANTLR route + PREPARE-name cache for reactive {@link RoutingConnection} and Sync routing.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class SqlClientRouteUtil {
	private SqlClientRouteUtil() {
	}

	/**
	 * Mutable prepare-name to route cache (thread-safe). Clear on connection close.
	 */
	public static ConcurrentMap<String, SqlRouteClassifier.Route> newPrepareRouteCache() {
		return new ConcurrentHashMap<>();
	}

	public static void cachePrepareRoute(
			ConcurrentMap<String, SqlRouteClassifier.Route> cache,
			String prepareName,
			String preparedBodySql
	) {
		if (cache == null || prepareName == null || prepareName.isBlank() || preparedBodySql == null) {
			return;
		}
		if (cache.size() >= SqlRouteClassifier.prepareNameCacheMax()) {
			cache.clear();
		}
		cache.put(prepareName.trim(), SqlRouteClassifier.classifyPreparedBody(preparedBodySql));
	}

	public static void onSql(
			ConcurrentMap<String, SqlRouteClassifier.Route> cache,
			String sql
	) {
		if (cache == null || sql == null) {
			return;
		}
		final SqlClientSql.PrepareParts parts = SqlClientSql.tryParsePrepare(sql);
		if (parts != null) {
			cachePrepareRoute(cache, parts.name(), parts.body());
		}
		if (SqlClientSql.isDeallocate(sql)) {
			final String name = SqlClientSql.tryParseDeallocateName(sql);
			if (name != null) {
				cache.remove(name);
			}
		}
	}

	public static SqlRouteClassifier.Route routeFor(
			ConcurrentMap<String, SqlRouteClassifier.Route> cache,
			String sql
	) {
		final String execName = SqlClientSql.tryParseExecuteName(sql);
		if (execName != null) {
			if (cache == null) {
				return SqlRouteClassifier.Route.WRITE;
			}
			return cache.getOrDefault(execName, SqlRouteClassifier.Route.WRITE);
		}
		return SqlRouteClassifier.classify(sql);
	}

	/**
	 * {@code true} when every statement routes READ (empty/null -> false).
	 */
	public static boolean allRead(
			ConcurrentMap<String, SqlRouteClassifier.Route> cache,
			List<String> sqls
	) {
		if (sqls == null || sqls.isEmpty()) {
			return false;
		}
		for (String sql : sqls) {
			onSql(cache, sql);
			if (routeFor(cache, sql) != SqlRouteClassifier.Route.READ) {
				return false;
			}
		}
		return true;
	}
}