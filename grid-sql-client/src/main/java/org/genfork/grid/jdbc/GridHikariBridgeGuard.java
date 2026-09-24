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

import java.sql.SQLException;

import javax.sql.DataSource;

/**
 * Fail-closed reject when Grid JDBC is wrapped by HikariCP. Grid multiplex is one TCP +
 * many logical sessions - Hikari {@code maximumPoolSize=N} sockets break that contract.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public final class GridHikariBridgeGuard {
	/** HikariCP package prefix (do not match our own {@code GridHikariBridgeGuard} name). */
	private static final String HIKARI_PACKAGE_PREFIX = "com.zaxxer.hikari";
	private static final String ERR_HIKARI_BRIDGE =
			"HikariCP must not wrap Grid JDBC; use GridDataSource / SyncConnectionFactory multiplex "
					+ "(one TCP, maxTxContexts logical sessions)";

	private GridHikariBridgeGuard() {
	}

	/**
	 * Reject when the call stack or an optional Datasource class name indicates HikariCP.
	 *
	 * @param dataSource optional Datasource being used as a pool wrapper; may be {@code null}
	 */
	public static void rejectIfHikari(DataSource dataSource) throws SQLException {
		if (dataSource != null && classNameLooksLikeHikari(dataSource.getClass().getName())) {
			throw new SQLException(ERR_HIKARI_BRIDGE);
		}
		rejectIfHikariOnStack();
	}

	/**
	 * Reject when the current call stack contains a HikariCP frame.
	 */
	public static void rejectIfHikariOnStack() throws SQLException {
		final StackTraceElement[] stack = Thread.currentThread().getStackTrace();
		for (StackTraceElement frame : stack) {
			if (classNameLooksLikeHikari(frame.getClassName())) {
				throw new SQLException(ERR_HIKARI_BRIDGE);
			}
		}
	}

	static boolean classNameLooksLikeHikari(String className) {
		return className != null && className.startsWith(HIKARI_PACKAGE_PREFIX);
	}
}