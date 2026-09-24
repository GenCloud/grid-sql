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
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.jooq.ConnectionProvider;
import org.jooq.exception.DataAccessException;

/**
 * jOOQ {@link ConnectionProvider} for Grid JDBC.
 * <p>
 * Prefer {@code jdbc:grid://} via {@link org.genfork.grid.jdbc.GridDriver} or
 * {@link org.genfork.grid.jdbc.GridDataSource}. Blocking {@link #acquire()} /
 * {@link #release(Connection)} is intentional at this SPI edge (jOOQ ConnectionProvider
 * is sync); do not call {@code .block()} on Reactor APIs here.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class GridConnectionProvider implements ConnectionProvider {
	private static final String ERR_NULL_CONNECTION = "connection";
	private static final String ERR_NULL_DATA_SOURCE = "dataSource";
	private static final String ERR_NULL_SUPPLIER = "supplier";
	private static final String ERR_NULL_JDBC_URL = "jdbcUrl";
	private static final String ERR_ACQUIRE = "Failed to acquire JDBC Connection";
	private static final String ERR_RELEASE = "Failed to close JDBC Connection";
	private static final String ERR_URL_PREFIX = "jdbc:grid://";
	private static final String ERR_BAD_URL = "Expected jdbc:grid:// URL, got: ";

	private final Mode mode;
	private final Connection connection;
	private final DataSource dataSource;
	private final Supplier<Connection> syncSupplier;
	private final String jdbcUrl;
	private final boolean closeOnRelease;

	private enum Mode {
		CONNECTION,
		DATA_SOURCE,
		SYNC_SUPPLIER,
		JDBC_URL
	}

	private GridConnectionProvider(
			Mode mode,
			Connection connection,
			DataSource dataSource,
			Supplier<Connection> syncSupplier,
			String jdbcUrl,
			boolean closeOnRelease
	) {
		this.mode = mode;
		this.connection = connection;
		this.dataSource = dataSource;
		this.syncSupplier = syncSupplier;
		this.jdbcUrl = jdbcUrl;
		this.closeOnRelease = closeOnRelease;
	}

	/**
	 * Wrap an existing JDBC connection (not closed on {@link #release(Connection)}).
	 */
	public static GridConnectionProvider of(Connection connection) {
		Objects.requireNonNull(connection, ERR_NULL_CONNECTION);
		return new GridConnectionProvider(Mode.CONNECTION, connection, null, null, null, false);
	}

	/**
	 * Borrow from a {@link DataSource} (closed on release).
	 * Prefer {@link org.genfork.grid.jdbc.GridDataSource} (not Hikari N-socket pools).
	 */
	public static GridConnectionProvider of(DataSource dataSource) {
		Objects.requireNonNull(dataSource, ERR_NULL_DATA_SOURCE);
		return new GridConnectionProvider(Mode.DATA_SOURCE, null, dataSource, null, null, true);
	}

	/**
	 * Sync SPI edge: blocking supplier that returns a JDBC {@link Connection}
	 * (typically {@code DriverManager.getConnection("jdbc:grid://…")}).
	 */
	public static GridConnectionProvider ofSync(Supplier<Connection> supplier) {
		Objects.requireNonNull(supplier, ERR_NULL_SUPPLIER);
		return new GridConnectionProvider(Mode.SYNC_SUPPLIER, null, null, supplier, null, true);
	}

	/**
	 * Open via {@link DriverManager} using a {@code jdbc:grid://} URL (GridDriver preferred).
	 */
	public static GridConnectionProvider ofJdbcUrl(String jdbcUrl) {
		Objects.requireNonNull(jdbcUrl, ERR_NULL_JDBC_URL);
		if (!jdbcUrl.startsWith(ERR_URL_PREFIX)) {
			throw new IllegalArgumentException(ERR_BAD_URL + jdbcUrl);
		}
		return new GridConnectionProvider(Mode.JDBC_URL, null, null, null, jdbcUrl, true);
	}

	@Override
	public Connection acquire() {
		try {
			return switch (mode) {
				case CONNECTION -> connection;
				case DATA_SOURCE -> dataSource.getConnection();
				case SYNC_SUPPLIER -> {
					final Connection acquired = syncSupplier.get();
					if (acquired == null) {
						throw new DataAccessException(ERR_ACQUIRE + ": supplier returned null");
					}
					yield acquired;
				}
				case JDBC_URL -> DriverManager.getConnection(jdbcUrl);
			};
		} catch (SQLException e) {
			throw new DataAccessException(ERR_ACQUIRE, e);
		}
	}

	@Override
	public void release(Connection released) {
		if (released == null || !closeOnRelease) {
			return;
		}
		try {
			released.close();
		} catch (SQLException e) {
			throw new DataAccessException(ERR_RELEASE, e);
		}
	}
}