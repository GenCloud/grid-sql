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

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import org.genfork.grid.jooq.dialect.GridDialect;
import org.jooq.Configuration;
import org.jooq.SQLDialect;
import org.jooq.conf.FetchTriggerValuesAfterReturning;
import org.jooq.conf.ParamType;
import org.jooq.conf.RenderKeywordCase;
import org.jooq.conf.RenderNameCase;
import org.jooq.conf.RenderOptionalKeyword;
import org.jooq.conf.RenderQuotedNames;
import org.jooq.conf.Settings;
import org.jooq.impl.DefaultConfiguration;

/**
 * Grid-tuned jOOQ host dialect / settings for SimplifiedSql string building.
 * <p>
 * This module is <strong>not</strong> a database engine. jOOQ only builds SQL strings
 * (and optionally executes them over JDBC). The server still parses with ANTLR
 * {@code SimplifiedSql}; product capacity remains on {@code grid://}
 * {@code ConnectionFactory}.
 * <p>
 * Host dialect: {@link SQLDialect#DEFAULT} only. Logical dialect capabilities live in
 * {@link GridDialect}; critical clauses (ON CONFLICT / RETURNING / TRUNCATE) are rendered
 * by Grid QueryParts via {@link GridDSL#insertInto} / {@link GridDSL#truncate} — never via
 * SQLITE / POSTGRES / MYSQL product dialects or VisitListener / ExecuteListener SQL rewrites.
 * <p>
 * jOOQ 3.19+ emits a one-shot WARN ({@code DefaultExecuteContext.logDefaultDialect}) when
 * executing under {@code DEFAULT}. That is expected for Grid (not a Spring misconfiguration);
 * Spring consumers silence it via {@code GridJooqSpringSupport} / logging config.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class GridSQL {
	/**
	 * Closest OSS host dialect for SimplifiedSql rendering (generic DEFAULT, not a vendor dialect).
	 */
	public static final SQLDialect DIALECT = SQLDialect.DEFAULT;

	private static final String MODULE_NAME = "grid-jooq";
	private static final String ERR_CONFIGURATION = "configuration";
	private static final String ERR_VENDOR_DIALECT_PREFIX =
			"Grid product path forbids vendor SQLDialect (use DEFAULT + GridDSL QueryParts): ";

	/** Vendor dialects that must never be the product render host. */
	private static final Set<SQLDialect> FORBIDDEN_PRODUCT_DIALECTS = EnumSet.of(
			SQLDialect.SQLITE,
			SQLDialect.POSTGRES,
			SQLDialect.MYSQL,
			SQLDialect.MARIADB,
			SQLDialect.H2,
			SQLDialect.HSQLDB,
			SQLDialect.DERBY,
			SQLDialect.FIREBIRD,
			SQLDialect.CUBRID,
			SQLDialect.IGNITE);

	private GridSQL() {
	}

	/**
	 * Settings tuned so rendered SQL is accepted by SimplifiedSql (unquoted names, {@code ?} binds).
	 * <p>
	 * {@link FetchTriggerValuesAfterReturning#NEVER} keeps native {@code RETURNING} when apps
	 * use jOOQ returning steps under DEFAULT (no {@code last_insert_rowid} emulation).
	 * <p>
	 * {@link RenderOptionalKeyword#ON} for field aliases forces {@code AS} so
	 * {@code fetchExists} renders {@code SELECT 1 AS one} (SimplifiedSql rejects bare {@code 1 one}).
	 */
	public static Settings settings() {
		return new Settings()
				.withRenderQuotedNames(RenderQuotedNames.NEVER)
				.withRenderNameCase(RenderNameCase.AS_IS)
				.withRenderKeywordCase(RenderKeywordCase.UPPER)
				.withRenderCatalog(false)
				.withRenderSchema(false)
				.withParamType(ParamType.INDEXED)
				.withRenderFormatted(false)
				.withRenderOptionalAsKeywordForFieldAliases(RenderOptionalKeyword.ON)
				.withFetchTriggerValuesAfterReturning(FetchTriggerValuesAfterReturning.NEVER);
	}

	/**
	 * Fresh configuration: {@link #DIALECT} + {@link #settings()}, no connection, no strip listeners.
	 */
	public static Configuration configuration() {
		final DefaultConfiguration configuration = new DefaultConfiguration();
		installProduct(configuration);
		return configuration;
	}

	/**
	 * Product install: host {@link #DIALECT} + {@link #settings()}.
	 * <p>
	 * Fail-fast if a forbidden vendor dialect is already set. Does <strong>not</strong> register
	 * VisitListener / ExecuteListener SQL rewrite (strip) listeners.
	 *
	 * @return the same configuration for chaining
	 */
	public static Configuration installProduct(Configuration configuration) {
		Objects.requireNonNull(configuration, ERR_CONFIGURATION);
		final SQLDialect current = configuration.dialect();
		if (current != null && isForbiddenProductDialect(current)) {
			throw new IllegalStateException(ERR_VENDOR_DIALECT_PREFIX + current);
		}
		configuration.set(DIALECT);
		configuration.set(settings());
		return configuration;
	}

	/**
	 * Overwrite any preset dialect with {@link #DIALECT} + {@link #settings()} (no fail-fast).
	 * Use when migrating configs that still carry SQLITE from soft-swap.
	 */
	public static Configuration installProductOverwrite(Configuration configuration) {
		Objects.requireNonNull(configuration, ERR_CONFIGURATION);
		configuration.set(DIALECT);
		configuration.set(settings());
		return configuration;
	}

	/** True when {@code dialect} is banned as a product render host. */
	public static boolean isForbiddenProductDialect(SQLDialect dialect) {
		if (dialect == null) {
			return false;
		}
		final SQLDialect family = dialect.family();
		return FORBIDDEN_PRODUCT_DIALECTS.contains(dialect)
				|| FORBIDDEN_PRODUCT_DIALECTS.contains(family);
	}

	/**
	 * Logical SimplifiedSql dialect (capabilities); host enum stays {@link #DIALECT}.
	 */
	public static GridDialect logicalDialect() {
		return GridDialect.INSTANCE;
	}

	/**
	 * Module id for diagnostics / logging (not a magic SQL token).
	 */
	public static String moduleName() {
		return MODULE_NAME;
	}
}
