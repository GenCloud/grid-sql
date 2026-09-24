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

import org.jooq.Configuration;
import org.jooq.SQLDialect;
import org.jooq.conf.ParamType;
import org.jooq.conf.RenderKeywordCase;
import org.jooq.conf.RenderNameCase;
import org.jooq.conf.RenderQuotedNames;
import org.jooq.conf.Settings;
import org.jooq.impl.DefaultConfiguration;

/**
 * Grid-tuned jOOQ dialect / settings for SimplifiedSql string building.
 * <p>
 * This module is <strong>not</strong> a database engine. jOOQ only builds SQL strings
 * (and optionally executes them over JDBC). The server still parses with ANTLR
 * {@code SimplifiedSql}; product capacity remains on {@code grid://}
 * {@code ConnectionFactory}.
 * <p>
 * Dialect: {@link SQLDialect#DEFAULT} (closest generic / ANSI-ish baseline). There is no
 * first-class Grid dialect enum in OSS jOOQ; do not switch to Postgres/MySQL dialects —
 * those emit dialect-specific syntax SimplifiedSql rejects.
 * <p>
 * Supported DSL surface (must match grammar):
 * <ul>
 *   <li>SELECT with EQ JOIN only ({@code ON col = col}); INNER/LEFT/RIGHT/FULL as in SimplifiedSql</li>
 *   <li>ORDER BY + LIMIT / OFFSET</li>
 *   <li>INSERT … VALUES; UPSERT … VALUES; INSERT … ON CONFLICT DO UPDATE|DO NOTHING</li>
 *   <li>UPDATE / DELETE with mandatory WHERE</li>
 * </ul>
 * Limits / avoid: quoted identifiers, INSERT SELECT, multi-table UPDATE SET from
 * vendor dialects, non-EQ JOIN predicates, vendor functions not in SimplifiedSql.
 * {@code DSLContext.insert…onConflict} under {@link SQLDialect#DEFAULT} may emit
 * MySQL {@code ON DUPLICATE KEY} — use {@code UPSERT} or a plain {@code ON CONFLICT}
 * string instead.
 * Do not use Hikari as the product DataSource — prefer
 * {@link org.genfork.grid.jdbc.GridDataSource} (TCP floor
 * {@link org.genfork.grid.jdbc.GridDataSource#MIN_TCP_CHANNELS}).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class GridSQL {
	/**
	 * Closest OSS dialect for SimplifiedSql rendering (generic DEFAULT, not a vendor dialect).
	 */
	public static final SQLDialect DIALECT = SQLDialect.DEFAULT;

	private static final String MODULE_NAME = "grid-jooq";

	private GridSQL() {
	}

	/**
	 * Settings tuned so rendered SQL is accepted by SimplifiedSql (unquoted names, {@code ?} binds).
	 */
	public static Settings settings() {
		return new Settings()
				.withRenderQuotedNames(RenderQuotedNames.NEVER)
				.withRenderNameCase(RenderNameCase.AS_IS)
				.withRenderKeywordCase(RenderKeywordCase.UPPER)
				.withRenderCatalog(false)
				.withRenderSchema(false)
				.withParamType(ParamType.INDEXED)
				.withRenderFormatted(false);
	}

	/**
	 * Fresh configuration: {@link #DIALECT} + {@link #settings()}, no connection.
	 */
	public static Configuration configuration() {
		final DefaultConfiguration configuration = new DefaultConfiguration();
		configuration.set(DIALECT);
		configuration.set(settings());
		return configuration;
	}

	/**
	 * Module id for diagnostics / logging (not a magic SQL token).
	 */
	public static String moduleName() {
		return MODULE_NAME;
	}
}
