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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Parses grid:// connection URLs (URI shape only — not JDBC/R2DBC).
 * <p>
 * Single host:
 * {@code grid://user:pass@host:15432/public?maxTxContexts=16}
 * <p>
 * Multi-host failover (comma-separated {@code host:port} in authority):
 * {@code grid://user:pass@h1:15432,h2:15433/public}
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class GridSqlUri {
	public static final String SCHEME = "grid";
	private static final String SCHEME_PREFIX = SCHEME + "://";
	private static final String QUERY_HOSTS_FORBIDDEN = "hosts";
	private static final char AUTHORITY_USER_SEP = '@';
	private static final char HOST_LIST_SEP = ',';
	private static final char HOST_PORT_SEP = ':';
	private static final char PATH_SEP = '/';
	private static final char QUERY_SEP = '?';
	private static final char USER_PASS_SEP = ':';
	private static final String QUERY_READ_ENDPOINTS = "readEndpoints";
	private static final String QUERY_READ_PREFERENCE = "readPreference";
	private static final String QUERY_MAX_READ_CONNECTIONS = "maxReadConnections";
	private static final String QUERY_STALE_READ_POLICY = "staleReadPolicy";
	private static final String QUERY_FETCH_WINDOW = "fetchWindow";
	private static final String ERR_REPLICA_WITHOUT_ENDPOINTS =
			"readPreference=REPLICA requires readEndpoints=host:port,...";

	private final List<HostEndpoint> endpoints;
	private final String user;
	private final String password;
	private final String schema;
	private final ConnectionOptions options;

	private GridSqlUri(
			List<HostEndpoint> endpoints,
			String user,
			String password,
			String schema,
			ConnectionOptions options
	) {
		this.endpoints = List.copyOf(endpoints);
		this.user = user;
		this.password = password;
		this.schema = schema;
		this.options = options;
	}

	public static GridSqlUri parse(String url) {
		Objects.requireNonNull(url, "url");
		final String trimmed = url.trim();
		if (!trimmed.toLowerCase(Locale.ROOT).startsWith(SCHEME_PREFIX)) {
			throw new IllegalArgumentException("URL must start with grid:// (got: " + url + ")");
		}
		final String rest = trimmed.substring(SCHEME_PREFIX.length());
		final int queryAt = indexOfUnescaped(rest, QUERY_SEP);
		final String beforeQuery = queryAt >= 0 ? rest.substring(0, queryAt) : rest;
		final String rawQuery = queryAt >= 0 ? rest.substring(queryAt + 1) : null;

		String user = "";
		String password = "";
		String authorityAndPath = beforeQuery;
		final int at = beforeQuery.lastIndexOf(AUTHORITY_USER_SEP);
		if (at >= 0) {
			final String userInfo = beforeQuery.substring(0, at);
			authorityAndPath = beforeQuery.substring(at + 1);
			final int colon = userInfo.indexOf(USER_PASS_SEP);
			if (colon < 0) {
				user = decode(userInfo);
			} else {
				user = decode(userInfo.substring(0, colon));
				password = decode(userInfo.substring(colon + 1));
			}
		}

		final int pathAt = authorityAndPath.indexOf(PATH_SEP);
		final String authority = pathAt >= 0 ? authorityAndPath.substring(0, pathAt) : authorityAndPath;
		String schema = ConnectionOptions.DEFAULT_SCHEMA;
		if (pathAt >= 0 && pathAt + 1 < authorityAndPath.length()) {
			schema = authorityAndPath.substring(pathAt + 1);
			final int slash = schema.indexOf(PATH_SEP);
			if (slash >= 0) {
				schema = schema.substring(0, slash);
			}
			if (schema.isBlank()) {
				schema = ConnectionOptions.DEFAULT_SCHEMA;
			}
		}

		final List<HostEndpoint> endpoints = parseEndpoints(authority);
		if (endpoints.isEmpty()) {
			throw new IllegalArgumentException("grid:// URL requires at least one host:port");
		}

		final Map<String, String> q = parseQuery(rawQuery);
		if (q.containsKey(QUERY_HOSTS_FORBIDDEN)) {
			throw new IllegalArgumentException(
					"grid:// multi-host must use comma-separated authority (host:port,host:port); ?"
							+ QUERY_HOSTS_FORBIDDEN + "= is not supported");
		}
		final ConnectionOptions.Builder ob = ConnectionOptions.builder();
		if (q.containsKey("minConnections")) {
			ob.minConnections(Math.max(1, parseInt(q.get("minConnections"), ConnectionOptions.DEFAULT_MIN_CONNECTIONS)));
		}
		if (q.containsKey("maxConnections")) {
			ob.maxConnections(Math.max(1, parseInt(q.get("maxConnections"), ConnectionOptions.DEFAULT_MAX_CONNECTIONS)));
		}
		if (q.containsKey("maxTxContexts")) {
			ob.maxTxContexts(parseInt(q.get("maxTxContexts"), ConnectionOptions.DEFAULT_MAX_TX_CONTEXTS));
		}
		if (q.containsKey("warmup")) {
			ob.warmup(parseBool(q.get("warmup"), ConnectionOptions.DEFAULT_WARMUP));
		}
		if (q.containsKey("execTimeoutMs")) {
			ob.execTimeout(parseTimeoutMs(q.get("execTimeoutMs")));
		}
		if (q.containsKey("connectTimeoutMs")) {
			ob.connectTimeout(parseTimeoutMs(q.get("connectTimeoutMs")));
		}
		if (q.containsKey("readTimeoutMs")) {
			ob.readTimeout(parseTimeoutMs(q.get("readTimeoutMs")));
		}
		if (q.containsKey("writeTimeoutMs")) {
			ob.writeTimeout(parseTimeoutMs(q.get("writeTimeoutMs")));
		}
		if (q.containsKey("maxRetries")) {
			ob.maxRetries(Math.max(0, parseInt(q.get("maxRetries"), ConnectionOptions.DEFAULT_MAX_RETRIES)));
		}
		if (q.containsKey("retryDelayMs")) {
			ob.retryDelay(parseTimeoutMs(q.get("retryDelayMs")));
		}
		if (q.containsKey("retryMode")) {
			ob.retryMode(parseRetryMode(q.get("retryMode")));
		}
		if (q.containsKey("timezone")) {
			ob.timezone(q.get("timezone"));
		}
		if (q.containsKey(QUERY_READ_PREFERENCE)) {
			ob.readPreference(ReadPreference.fromWireName(q.get(QUERY_READ_PREFERENCE)));
		}
		if (q.containsKey(QUERY_STALE_READ_POLICY)) {
			ob.staleReadPolicy(StaleReadPolicy.fromWireName(q.get(QUERY_STALE_READ_POLICY)));
		}
		if (q.containsKey(QUERY_MAX_READ_CONNECTIONS)) {
			ob.maxReadConnections(Math.max(1, parseInt(q.get(QUERY_MAX_READ_CONNECTIONS),
					ConnectionOptions.DEFAULT_MAX_READ_CONNECTIONS)));
		}
		if (q.containsKey(QUERY_READ_ENDPOINTS)) {
			ob.readEndpoints(parseEndpoints(q.get(QUERY_READ_ENDPOINTS)));
		}
		if (q.containsKey(QUERY_FETCH_WINDOW)) {
			ob.fetchWindow(Math.max(1, parseInt(q.get(QUERY_FETCH_WINDOW),
					ConnectionOptions.DEFAULT_FETCH_WINDOW)));
		}
		final ConnectionOptions options = ob.build();
		if (options.readPreference() == ReadPreference.REPLICA && options.readEndpoints().isEmpty()) {
			throw new IllegalArgumentException(ERR_REPLICA_WITHOUT_ENDPOINTS);
		}
		return new GridSqlUri(endpoints, user, password, schema, options);
	}

	private static List<HostEndpoint> parseEndpoints(String authority) {
		if (authority == null || authority.isBlank()) {
			return List.of();
		}
		final List<HostEndpoint> out = new ArrayList<>();
		for (String part : authority.split(String.valueOf(HOST_LIST_SEP))) {
			final String trimmed = part.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			final int colon = trimmed.lastIndexOf(HOST_PORT_SEP);
			if (colon <= 0) {
				out.add(new HostEndpoint(trimmed, ConnectionOptions.DEFAULT_PORT));
				continue;
			}
			final String host = trimmed.substring(0, colon).trim();
			final String portRaw = trimmed.substring(colon + 1).trim();
			if (host.isEmpty()) {
				throw new IllegalArgumentException("empty host in authority: " + authority);
			}
			final int port = portRaw.isEmpty()
					? ConnectionOptions.DEFAULT_PORT
					: Integer.parseInt(portRaw);
			out.add(new HostEndpoint(host, port));
		}
		return out;
	}

	private static int indexOfUnescaped(String s, char ch) {
		return s.indexOf(ch);
	}

	private static Map<String, String> parseQuery(String raw) {
		final Map<String, String> map = new LinkedHashMap<>();
		if (raw == null || raw.isBlank()) {
			return map;
		}
		for (String part : raw.split("&")) {
			if (part.isEmpty()) {
				continue;
			}
			final int eq = part.indexOf('=');
			if (eq < 0) {
				map.put(decode(part), "");
			} else {
				map.put(decode(part.substring(0, eq)), decode(part.substring(eq + 1)));
			}
		}
		return map;
	}

	private static String decode(String s) {
		return URLDecoder.decode(s, StandardCharsets.UTF_8);
	}

	private static int parseInt(String raw, int def) {
		if (raw == null || raw.isBlank()) {
			return def;
		}
		return Integer.parseInt(raw.trim());
	}

	private static boolean parseBool(String raw, boolean def) {
		if (raw == null || raw.isBlank()) {
			return def;
		}
		return Boolean.parseBoolean(raw.trim());
	}

	/** Absent or {@code <=0} → {@link Duration#ZERO} (disabled). */
	private static Duration parseTimeoutMs(String raw) {
		if (raw == null || raw.isBlank()) {
			return Duration.ZERO;
		}
		final long ms = Long.parseLong(raw.trim());
		if (ms <= 0L) {
			return Duration.ZERO;
		}
		return Duration.ofMillis(ms);
	}

	private static RetryMode parseRetryMode(String raw) {
		if (raw == null || raw.isBlank()) {
			return ConnectionOptions.DEFAULT_RETRY_MODE;
		}
		return RetryMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
	}

	/** Primary / first endpoint host (sticky start). */
	public String host() {
		return endpoints.getFirst().host();
	}

	/** Primary / first endpoint port. */
	public int port() {
		return endpoints.getFirst().port();
	}

	public List<HostEndpoint> endpoints() {
		return endpoints;
	}

	public String user() {
		return user;
	}

	public String password() {
		return password;
	}

	public String schema() {
		return schema;
	}

	public ConnectionOptions options() {
		return options;
	}

	public int maxConnections() {
		return options.maxConnections();
	}

	public int maxTxContexts() {
		return options.maxTxContexts();
	}

	public boolean warmup() {
		return options.warmup();
	}

	public Duration execTimeout() {
		return options.execTimeout();
	}
}
