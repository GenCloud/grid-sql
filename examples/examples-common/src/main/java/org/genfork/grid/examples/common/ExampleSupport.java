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
package org.genfork.grid.examples.common;

import org.genfork.grid.jdbc.GridDriver;
import org.genfork.grid.jdbc.GridJdbcUrls;
import org.genfork.grid.sql.client.ConnectionFactory;
import org.genfork.grid.sql.client.Row;
import org.genfork.grid.sql.client.RowMetadata;
import reactor.core.publisher.Mono;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Shared bootstrap for runnable {@code grid-sql-client} examples (reactive + JDBC).
 * <p>
 * Resolves {@value #ENV_GRID_URL}, optionally calls {@link ConnectionFactory#warmup()}
 * when the URL has {@code warmup=true}, runs at the sync {@code main} edge, and always
 * {@link ConnectionFactory#dispose() disposes} the factory. Borrow channels via
 * {@link ConnectionFactory#obtain()}. JDBC examples use {@link #runJdbc}.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class ExampleSupport {
	/** Environment variable overriding the default {@code grid://} URL. */
	public static final String ENV_GRID_URL = "GRID_URL";

	/** Solo / primary SQL port (capacity or primary profile). */
	public static final String DEFAULT_URL = "grid://grid:grid@127.0.0.1:15432/public";

	/**
	 * Product-path URL: optional preheat via {@link ConnectionFactory#warmup()}.
	 * Matches README / {@code SqlCli} ({@code warmup=true}).
	 */
	public static final String DEFAULT_WARMUP_URL =
			DEFAULT_URL + "?warmup=true&minConnections=1&maxConnections=2";

	/**
	 * Writer + replica-read routing for {@code examples/compose/1dc-n2}
	 * ({@code readPreference=REPLICA}).
	 */
	public static final String DEFAULT_REPLICA_URL =
			"grid://grid:grid@127.0.0.1:15432/public"
					+ "?readEndpoints=127.0.0.1:15433&readPreference=REPLICA&warmup=true";

	/**
	 * Multi-host writer candidate list (1dc-n2 / primary+replica). Sticky, not LB.
	 */
	public static final String DEFAULT_HA_URL =
			"grid://grid:grid@127.0.0.1:15432,127.0.0.1:15433/public?warmup=true&retryMode=FIXED&maxRetries=2";

	/** Default JDBC tooling URL ({@code jdbc:grid://…}). */
	public static final String DEFAULT_JDBC_URL = toJdbcUrl(DEFAULT_WARMUP_URL);

	private ExampleSupport() {
	}

	/**
	 * {@link #ENV_GRID_URL} when set and non-blank; otherwise {@link #DEFAULT_WARMUP_URL}.
	 */
	public static String gridUrl() {
		return gridUrl(DEFAULT_WARMUP_URL);
	}

	/**
	 * {@link #ENV_GRID_URL} when set and non-blank; otherwise {@code defaultUrl}.
	 */
	public static String gridUrl(String defaultUrl) {
		final String fromEnv = System.getenv(ENV_GRID_URL);
		if (fromEnv == null || fromEnv.isBlank()) {
			return Objects.requireNonNull(defaultUrl, "defaultUrl");
		}
		return fromEnv.trim();
	}

	/**
	 * JDBC URL for tooling: {@code jdbc:} + {@link #gridUrl()} (or already {@code jdbc:grid://}).
	 */
	public static String jdbcUrl() {
		return toJdbcUrl(gridUrl());
	}

	/**
	 * JDBC URL from a default {@code grid://} (honours {@link #ENV_GRID_URL}).
	 */
	public static String jdbcUrl(String defaultGridUrl) {
		return toJdbcUrl(gridUrl(defaultGridUrl));
	}

	/**
	 * Ensure {@code jdbc:grid://…} form (accepts plain {@code grid://} or already-prefixed).
	 */
	public static String toJdbcUrl(String gridOrJdbcUrl) {
		Objects.requireNonNull(gridOrJdbcUrl, "gridOrJdbcUrl");
		final String trimmed = gridOrJdbcUrl.trim();
		if (trimmed.regionMatches(true, 0, GridJdbcUrls.JDBC_GRID_PREFIX, 0,
				GridJdbcUrls.JDBC_GRID_PREFIX.length())) {
			return trimmed;
		}
		if (trimmed.regionMatches(true, 0, GridJdbcUrls.GRID_SCHEME_PREFIX, 0,
				GridJdbcUrls.GRID_SCHEME_PREFIX.length())) {
			return GridJdbcUrls.JDBC_PREFIX + trimmed;
		}
		throw new IllegalArgumentException(
				"expected grid:// or jdbc:grid:// URL, got: " + trimmed);
	}

	/**
	 * Create a factory from {@link #gridUrl(String)}, {@link ConnectionFactory#warmup()},
	 * run {@code body}, block at the application edge, then dispose.
	 */
	public static void run(String defaultUrl, Function<ConnectionFactory, Mono<?>> body) {
		Objects.requireNonNull(body, "body");
		final ConnectionFactory factory = ConnectionFactory.fromUrl(gridUrl(defaultUrl));
		try {
			factory.warmup()
					.then(Mono.defer(() -> body.apply(factory).then()))
					.block();
		} finally {
			factory.dispose();
		}
	}

	/**
	 * Same as {@link #run(String, Function)} with {@link #DEFAULT_WARMUP_URL}.
	 */
	public static void run(Function<ConnectionFactory, Mono<?>> body) {
		run(DEFAULT_WARMUP_URL, body);
	}

	/**
	 * Create a factory without calling {@link ConnectionFactory#warmup()} first.
	 * Used by {@code examples-warmup} to show cold-pool behaviour.
	 */
	public static void runWithoutWarmup(String defaultUrl, Function<ConnectionFactory, Mono<?>> body) {
		Objects.requireNonNull(body, "body");
		final ConnectionFactory factory = ConnectionFactory.fromUrl(gridUrl(defaultUrl));
		try {
			body.apply(factory).then().block();
		} finally {
			factory.dispose();
		}
	}

	/**
	 * Open a JDBC {@link Connection} via {@link DriverManager} and run {@code body}.
	 * No Reactor {@code .block()} — the driver uses {@code JdbcSync}/{@code SyncAwait}.
	 */
	public static void runJdbc(JdbcBody body) throws SQLException {
		runJdbc(DEFAULT_WARMUP_URL, body);
	}

	/**
	 * Open a JDBC connection for {@link #jdbcUrl(String)} and run {@code body}.
	 */
	public static void runJdbc(String defaultGridUrl, JdbcBody body) throws SQLException {
		Objects.requireNonNull(body, "body");
		try {
			Class.forName(GridDriver.class.getName());
		} catch (ClassNotFoundException e) {
			throw new SQLException("GridDriver not on classpath", e);
		}
		final String url = jdbcUrl(defaultGridUrl);
		println("JDBC URL: " + url);
		try (Connection connection = DriverManager.getConnection(url)) {
			body.run(connection);
		}
	}

	/**
	 * Compact single-line row dump for stdout demos.
	 */
	public static String formatRow(Row row, RowMetadata meta) {
		Objects.requireNonNull(row, "row");
		if (meta == null || meta.getColumnCount() <= 0) {
			return String.valueOf(row.get(0));
		}
		final List<String> names = meta.getColumnNames();
		final StringBuilder sb = new StringBuilder(64);
		final int n = meta.getColumnCount();
		for (int i = 0; i < n; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			if (names != null && i < names.size()) {
				sb.append(names.get(i));
			} else {
				sb.append(i);
			}
			sb.append('=');
			sb.append(row.get(i));
		}
		return sb.toString();
	}

	/**
	 * Print {@code message} to stdout (examples are CLI demos).
	 */
	public static void println(String message) {
		System.out.println(message);
	}

	/**
	 * Checked body for {@link #runJdbc(JdbcBody)}.
	 */
	@FunctionalInterface
	public interface JdbcBody {
		void run(Connection connection) throws SQLException;
	}
}