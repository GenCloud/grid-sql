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

import java.sql.Connection;
import java.util.Objects;

import javax.sql.DataSource;

import org.jooq.Configuration;
import org.jooq.ConnectionProvider;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;

/**
 * Factory for {@link DSLContext} tuned via {@link GridSQL}.
 * <p>
 * Prefer JDBC execute over {@code jdbc:grid://} ({@link org.genfork.grid.jdbc.GridDriver})
 * or multiplex {@link org.genfork.grid.jdbc.GridDataSource} (floor
 * {@link org.genfork.grid.jdbc.GridDataSource#MIN_TCP_CHANNELS} TCP × {@code maxTxContexts}).
 * Hikari N-socket pools are not the product path — use {@link org.genfork.grid.jdbc.GridDataSource}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class GridDSL {
	private static final String ERR_NULL_CONNECTION = "connection";
	private static final String ERR_NULL_DATA_SOURCE = "dataSource";
	private static final String ERR_NULL_PROVIDER = "connectionProvider";
	private static final String ERR_NULL_JDBC_URL = "jdbcUrl";

	private GridDSL() {
	}

	/**
	 * Render-only context (no JDBC connection).
	 */
	public static DSLContext using() {
		return DSL.using(GridSQL.configuration());
	}

	/**
	 * DSL over an existing JDBC connection (prefer {@code jdbc:grid://}).
	 */
	public static DSLContext using(Connection connection) {
		Objects.requireNonNull(connection, ERR_NULL_CONNECTION);
		return DSL.using(connection, GridSQL.DIALECT, GridSQL.settings());
	}

	/**
	 * DSL over a {@link DataSource} (prefer {@link org.genfork.grid.jdbc.GridDataSource}).
	 */
	public static DSLContext using(DataSource dataSource) {
		Objects.requireNonNull(dataSource, ERR_NULL_DATA_SOURCE);
		return DSL.using(dataSource, GridSQL.DIALECT, GridSQL.settings());
	}

	/**
	 * DSL over a custom {@link ConnectionProvider} (e.g. {@link GridConnectionProvider}).
	 */
	public static DSLContext using(ConnectionProvider connectionProvider) {
		Objects.requireNonNull(connectionProvider, ERR_NULL_PROVIDER);
		final DefaultConfiguration configuration = new DefaultConfiguration();
		configuration.set(GridSQL.DIALECT);
		configuration.set(GridSQL.settings());
		configuration.set(connectionProvider);
		return DSL.using(configuration);
	}

	/**
	 * Open via {@code jdbc:grid://} URL through {@link GridConnectionProvider#ofJdbcUrl(String)}.
	 */
	public static DSLContext usingJdbcUrl(String jdbcUrl) {
		Objects.requireNonNull(jdbcUrl, ERR_NULL_JDBC_URL);
		return using(GridConnectionProvider.ofJdbcUrl(jdbcUrl));
	}

	/**
	 * Copy of {@link GridSQL#configuration()} for callers that mutate settings further.
	 */
	public static Configuration newConfiguration() {
		return GridSQL.configuration();
	}
}