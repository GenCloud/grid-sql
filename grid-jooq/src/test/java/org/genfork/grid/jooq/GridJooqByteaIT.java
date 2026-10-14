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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * jOOQ {@code VARBINARY} / {@code byte[]} bind round-trip (fork OTHER-field parity).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class GridJooqByteaIT {
	private static final String TABLE = "bin_jooq";
	private static final String COL_ID = "id";
	private static final String COL_PAYLOAD = "payload";

	private SqlEngine engine;
	private SqlServer server;
	private int port;
	private GridDataSource dataSource;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(new TableCatalog(Files.createTempDirectory("jooq-bytea-it")), null, 4);
		engine.execute("CREATE TABLE bin_jooq (id INT PRIMARY KEY, payload BYTEA)");
		port = freePort();
		server = new SqlServer("127.0.0.1", port, engine, "u", "p", 64);
		server.start();
		TimeUnit.MILLISECONDS.sleep(120);
		Class.forName("org.genfork.grid.jdbc.GridDriver");
		dataSource = new GridDataSource("jdbc:grid://u:p@127.0.0.1:" + port + "/public");
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
	void varbinaryFieldInsertSelect() {
		final DSLContext dsl = GridDSL.using(dataSource);
		final Table<Record> t = table(name(TABLE));
		final Field<Integer> id = field(name(COL_ID), Integer.class);
		final Field<byte[]> payload = field(name(COL_PAYLOAD), SQLDataType.VARBINARY);
		final byte[] raw = new byte[]{0x00, 0x7f, (byte) 0xff};

		assertEquals(1, dsl.insertInto(t).columns(id, payload).values(1, raw).execute());
		assertArrayEquals(raw, dsl.select(payload).from(t).where(id.eq(1)).fetchOne(payload));
	}

	@Test
	void objectFieldBindParityWithForkOther() {
		final DSLContext dsl = GridDSL.using(dataSource);
		final Table<Record> t = table(name(TABLE));
		final Field<Integer> id = field(name(COL_ID), Integer.class);
		final Field<Object> payload = field(name(COL_PAYLOAD), Object.class);
		final byte[] raw = new byte[]{1, 2, 3, 4};

		assertEquals(1, dsl.insertInto(t).columns(id, payload).values(2, raw).execute());
		final Object cell = dsl.select(payload).from(t).where(id.eq(2)).fetchOne(payload);
		assertTrue(cell instanceof byte[]);
		assertArrayEquals(raw, (byte[]) cell);
	}

	private static int freePort() throws Exception {
		try (ServerSocket ss = new ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
	}
}
