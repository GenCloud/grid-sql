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

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.jdbc.GridDataSource;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.netty.SqlServer;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Round-trip jOOQ CRUD over multiplex {@link GridDataSource} (no Hikari).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class GridJooqJdbcIT {
	private static final String TABLE = "accounts";
	private static final String COL_ID = "id";
	private static final String COL_BALANCE = "balance";

	private SqlEngine engine;
	private SqlServer server;
	private int port;
	private GridDataSource dataSource;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("jooq-jdbc-it")), null, 4);
		engine.execute("CREATE TABLE accounts (id INT PRIMARY KEY, balance INT)");
		port = freePort();
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 64);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		Class.forName("org.genfork.grid.jdbc.GridDriver");
		dataSource = new GridDataSource("jdbc:grid://u:p@127.0.0.1:" + port + "/public");
		assertTrue(dataSource.tcpChannelFloor() >= GridDataSource.MIN_TCP_CHANNELS);
	}

	@AfterEach
	void tearDown() {
		if (dataSource != null) {
			dataSource.close();
			dataSource = null;
		}
		if (server != null) {
			server.close();
			server = null;
		}
	}

	@Test
	void dataSourceConnectionProviderCrud() {
		final DefaultConfiguration configuration = new DefaultConfiguration();
		configuration.set(GridSQL.DIALECT);
		configuration.set(GridSQL.settings());
		configuration.set(new DataSourceConnectionProvider(dataSource));
		final DSLContext dsl = new DefaultDSLContext(configuration);

		final Table<Record> accounts = table(name(TABLE));
		final Field<Integer> id = field(name(COL_ID), Integer.class);
		final Field<Integer> balance = field(name(COL_BALANCE), Integer.class);

		assertEquals(1, dsl.insertInto(accounts).columns(id, balance).values(1, 100).execute());
		assertEquals(Integer.valueOf(100),
				dsl.select(balance).from(accounts).where(id.eq(1)).fetchOne(balance));
		assertEquals(1, dsl.update(accounts).set(balance, 150).where(id.eq(1)).execute());
		assertEquals(Integer.valueOf(150),
				dsl.select(balance).from(accounts).where(id.eq(1)).fetchOne(balance));
		assertEquals(1, dsl.deleteFrom(accounts).where(id.eq(1)).execute());
	}

	@Test
	void gridDslUsingDataSource() {
		final DSLContext dsl = GridDSL.using(dataSource);
		final Table<Record> accounts = table(name(TABLE));
		final Field<Integer> id = field(name(COL_ID), Integer.class);
		final Field<Integer> balance = field(name(COL_BALANCE), Integer.class);
		dsl.insertInto(accounts).columns(id, balance).values(2, 200).execute();
		assertEquals(Integer.valueOf(200),
				dsl.select(balance).from(accounts).where(id.eq(2)).fetchOne(balance));
	}

	private static int freePort() throws Exception {
		try (ServerSocket ss = new ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
	}
}