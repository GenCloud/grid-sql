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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

import org.genfork.grid.jooq.dialect.GridInsertOnConflict;
import org.genfork.grid.jooq.dialect.GridReturningInsert;
import org.genfork.grid.jooq.dialect.GridTruncate;
import org.genfork.grid.jdbc.GridConnection;
import org.genfork.grid.sql.client.BatchStatement;
import org.genfork.grid.sql.netty.SqlWire;
import org.jooq.Configuration;
import org.jooq.ConnectionProvider;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Table;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;

/**
 * Factory for {@link DSLContext} tuned via {@link GridSQL}, plus Grid-owned DML entry points.
 * <p>
 * Prefer JDBC execute over {@code jdbc:grid://} ({@link org.genfork.grid.jdbc.GridDriver})
 * or multiplex {@link org.genfork.grid.jdbc.GridDataSource} (floor
 * {@link org.genfork.grid.jdbc.GridDataSource#MIN_TCP_CHANNELS} TCP × {@code maxTxContexts}).
 * Hikari N-socket pools are not the product path — use {@link org.genfork.grid.jdbc.GridDataSource}.
 * <p>
 * Conflict / TRUNCATE / RETURNING: use {@link #insertInto(DSLContext, Table)},
 * {@link #truncate(DSLContext, Table)}, {@link #insertReturning(DSLContext, Table)} — not
 * jOOQ DEFAULT {@code onConflict} ({@code ON DUPLICATE KEY}) or SQLITE soft-swap.
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
	private static final String ERR_NULL_DSL = "dsl";
	private static final String ERR_NULL_TABLE = "table";
	private static final String ERR_NULL_CONFIGURATION = "configuration";
	private static final String ERR_NULL_QUERIES = "queries";

	private GridDSL() {
	}

	/**
	 * Render-only context (no JDBC connection).
	 */
	public static DSLContext using() {
		return DSL.using(GridSQL.configuration());
	}

	/**
	 * DSL over an installed product {@link Configuration}.
	 */
	public static DSLContext using(Configuration configuration) {
		Objects.requireNonNull(configuration, ERR_NULL_CONFIGURATION);
		return DSL.using(configuration);
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
		GridSQL.installProduct(configuration);
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

	/**
	 * Start Grid-owned {@code INSERT … ON CONFLICT …}.
	 */
	public static GridInsertOnConflict.Builder insertInto(DSLContext dsl, Table<?> table) {
		Objects.requireNonNull(dsl, ERR_NULL_DSL);
		Objects.requireNonNull(table, ERR_NULL_TABLE);
		return new GridInsertOnConflict.Builder(dsl, table);
	}

	/**
	 * Start Grid-owned {@code INSERT … RETURNING …} (no conflict clause).
	 */
	public static GridReturningInsert.Builder insertReturning(DSLContext dsl, Table<?> table) {
		Objects.requireNonNull(dsl, ERR_NULL_DSL);
		Objects.requireNonNull(table, ERR_NULL_TABLE);
		return new GridReturningInsert.Builder(dsl, table);
	}

	/**
	 * Execute {@code TRUNCATE TABLE}.
	 */
	public static int truncate(DSLContext dsl, Table<?> table) {
		Objects.requireNonNull(dsl, ERR_NULL_DSL);
		Objects.requireNonNull(table, ERR_NULL_TABLE);
		return new GridTruncate(table).execute(dsl);
	}

	/**
	 * {@code TRUNCATE TABLE} as {@link Query}.
	 */
	public static Query truncateQuery(DSLContext dsl, Table<?> table) {
		Objects.requireNonNull(dsl, ERR_NULL_DSL);
		Objects.requireNonNull(table, ERR_NULL_TABLE);
		return new GridTruncate(table).toQuery(dsl);
	}

	/**
	 * Render-only {@code TRUNCATE TABLE} SQL.
	 */
	public static String truncateSql(Table<?> table) {
		return GridTruncate.sql(table);
	}

	/**
	 * Execute queries as one bound {@code BATCH_EXEC} when the JDBC connection is {@link GridConnection};
	 * otherwise falls back to per-query {@link Query#execute()}.
	 * <p>
	 * Uses {@link ConnectionProvider#acquire()} / {@link ConnectionProvider#release(Connection)}.
	 * For open JDBC transactions the provider must return the <em>same</em> {@link GridConnection}
	 * that holds the TX (e.g. {@code GridConnectionProvider.of(connection)}); a provider that opens a
	 * new connection per acquire will run the batch outside that TX.
	 */
	public static void executeBatchBound(DSLContext dsl, List<? extends Query> queries) {
		Objects.requireNonNull(dsl, ERR_NULL_DSL);
		if (queries == null || queries.isEmpty()) {
			return;
		}
		final ConnectionProvider provider = dsl.configuration().connectionProvider();
		final Connection jdbc = Objects.requireNonNull(provider.acquire(), ERR_NULL_CONNECTION);
		try {
			final List<SqlWire.BatchStatement> statements = BatchStatement.toWire(buildBatchStatements(queries));
			if (statements.isEmpty()) {
				return;
			}
			if (jdbc instanceof GridConnection grid) {
				try {
					grid.executeBatchBound(statements);
				} catch (SQLException ex) {
					throw new IllegalStateException(ex.getMessage(), ex);
				}
				return;
			}
			for (Query query : queries) {
				if (query != null) {
					query.execute();
				}
			}
		} finally {
			provider.release(jdbc);
		}
	}

	/**
	 * Render jOOQ queries as SPI {@link BatchStatement} slots (indexed SQL + binds).
	 */
	public static List<BatchStatement> buildBatchStatements(List<? extends Query> queries) {
		Objects.requireNonNull(queries, ERR_NULL_QUERIES);
		final List<BatchStatement> statements = new ArrayList<>();
		for (Query query : queries) {
			if (query == null) {
				continue;
			}
			statements.add(new BatchStatement(
					query.getSQL(ParamType.INDEXED),
					query.getBindValues().toArray()));
		}
		return List.copyOf(statements);
	}
}
