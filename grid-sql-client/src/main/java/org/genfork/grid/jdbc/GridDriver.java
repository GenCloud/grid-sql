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
package org.genfork.grid.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

import org.genfork.grid.sql.client.GridSqlUri;
import org.genfork.grid.sql.client.sync.SyncConnection;
import org.genfork.grid.sql.client.sync.SyncConnectionFactory;

/**
 * JDBC 4.3 driver for {@code jdbc:grid://} tooling URLs (DBeaver / IDE).
 * <p>
 * Same Sync* path as {@link GridDataSource}: {@link SyncConnectionFactory#shared}
 * (writer + optional READ_REPLICA) + {@link SyncConnectionFactory#open()}.
 * {@link Connection#close()} parks the channel and releases one Driver retain;
 * dispose of the interned factory only when all retains reach zero (DataSource + Driver).
 * Never hand-wires {@code RemoteConnectionFactory}.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class GridDriver implements Driver {
	public static final int MAJOR_VERSION = 1;
	public static final int MINOR_VERSION = 0;
	private static final String PROP_USER = "user";
	private static final String PROP_PASSWORD = "password";

	static {
		try {
			DriverManager.registerDriver(new GridDriver());
		} catch (SQLException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	@Override
	public Connection connect(String url, Properties info) throws SQLException {
		if (!acceptsURL(url)) {
			return null;
		}
		GridHikariBridgeGuard.rejectIfHikariOnStack();
		final String gridUrl = GridJdbcUrls.toGridUrl(url);
		final GridSqlUri parsed = GridSqlUri.parse(gridUrl);
		String user = parsed.user();
		String password = parsed.password();
		if (info != null) {
			if (info.getProperty(PROP_USER) != null) {
				user = info.getProperty(PROP_USER);
			}
			if (info.getProperty(PROP_PASSWORD) != null) {
				password = info.getProperty(PROP_PASSWORD);
			}
		}
		final SyncConnectionFactory syncFactory = SyncConnectionFactory.shared(
				parsed.endpoints(),
				user,
				password,
				parsed.options(),
				parsed.schema());
		syncFactory.retain();
		try {
			final SyncConnection sync = syncFactory.open();
			return new GridConnection(syncFactory, sync, true);
		} catch (RuntimeException e) {
			syncFactory.close();
			throw JdbcSync.toSqlException(e);
		}
	}

	@Override
	public boolean acceptsURL(String url) {
		return GridJdbcUrls.accepts(url);
	}

	@Override
	public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
		final DriverPropertyInfo user = new DriverPropertyInfo(
				PROP_USER, info == null ? null : info.getProperty(PROP_USER));
		user.required = false;
		final DriverPropertyInfo password = new DriverPropertyInfo(
				PROP_PASSWORD, info == null ? null : info.getProperty(PROP_PASSWORD));
		password.required = false;
		return new DriverPropertyInfo[]{user, password};
	}

	@Override
	public int getMajorVersion() {
		return MAJOR_VERSION;
	}

	@Override
	public int getMinorVersion() {
		return MINOR_VERSION;
	}

	@Override
	public boolean jdbcCompliant() {
		return false;
	}

	@Override
	public Logger getParentLogger() throws SQLFeatureNotSupportedException {
		throw new SQLFeatureNotSupportedException("getParentLogger");
	}
}