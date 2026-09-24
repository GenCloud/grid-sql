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
package org.genfork.grid.examples.jooq;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.sql.SQLException;

import org.genfork.grid.examples.common.ExampleSupport;
import org.genfork.grid.jdbc.GridDataSource;
import org.genfork.grid.jooq.GridDSL;
import org.genfork.grid.jooq.GridSQL;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;

/**
 * Thin jOOQ drop-in: multiplex {@link GridDataSource} (floor 10 TCP) + {@link GridSQL} settings.
 * <p>
 * Do <strong>not</strong> use Hikari — it breaks Grid multiplex. Codegen Tables stay in the
 * consumer app via {@code JDBCDatabase} + {@code jdbc:grid://}.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.0
 */
public final class JooqExample {
	private static final String TABLE = "accounts";
	private static final String COL_ID = "id";
	private static final String COL_BALANCE = "balance";

	private JooqExample() {
	}

	public static void main(String[] args) throws SQLException {
		final String jdbcUrl = ExampleSupport.jdbcUrl();
		try (GridDataSource ds = new GridDataSource(jdbcUrl)) {
			ExampleSupport.println("tcpChannelFloor=" + ds.tcpChannelFloor()
					+ " activeChannels=" + ds.syncFactory().activeChannels()
					+ " maxTxContexts=" + ds.syncFactory().maxTxContexts());

			final DefaultConfiguration configuration = new DefaultConfiguration();
			configuration.set(GridSQL.DIALECT);
			configuration.set(GridSQL.settings());
			configuration.set(new DataSourceConnectionProvider(ds));
			final DSLContext dsl = new DefaultDSLContext(configuration);

			final Table<Record> accounts = table(name(TABLE));
			final Field<Integer> id = field(name(COL_ID), Integer.class);
			final Field<Integer> balance = field(name(COL_BALANCE), Integer.class);

			dsl.execute("CREATE TABLE IF NOT EXISTS accounts (id INT PRIMARY KEY, balance INT)");
			dsl.deleteFrom(accounts).where(id.eq(1)).execute();
			dsl.insertInto(accounts).columns(id, balance).values(1, 100).execute();
			final Integer bal = dsl.select(balance).from(accounts).where(id.eq(1)).fetchOne(balance);
			ExampleSupport.println("balance=" + bal);

			final DSLContext viaGridDsl = GridDSL.using(ds);
			ExampleSupport.println("GridDSL dialect=" + viaGridDsl.dialect());
		}
	}
}