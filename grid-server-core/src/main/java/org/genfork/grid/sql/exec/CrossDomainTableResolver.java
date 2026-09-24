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
package org.genfork.grid.sql.exec;

import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.store.TableStore;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-domain JOIN table resolver: catalog lookup plus explicit alias registry.
 * <p>
 * Aliases map a short name to a qualified catalog table without string SQL parse.
 * Resolution order: alias registry → {@link SqlTableResolver#resolveTable}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class CrossDomainTableResolver {
	private final SqlTableResolver tables;
	/** Lower-case alias → qualified catalog table name. */
	private final ConcurrentHashMap<String, String> aliases = new ConcurrentHashMap<>();

	public CrossDomainTableResolver(SqlTableResolver tables) {
		this.tables = Objects.requireNonNull(tables, "tables");
	}

	/**
	 * Register {@code alias} as a synonym for {@code qualifiedTable} (catalog name).
	 */
	public void registerAlias(String alias, String qualifiedTable) {
		Objects.requireNonNull(alias, "alias");
		Objects.requireNonNull(qualifiedTable, "qualifiedTable");
		final String aliasKey = normalize(alias);
		final String tableKey = normalize(qualifiedTable);
		if (aliasKey.isEmpty() || tableKey.isEmpty()) {
			throw new IllegalArgumentException("alias and table required");
		}
		if (tables.catalog().getStore(tableKey) == null
				&& tables.catalog().getSchema(tableKey) == null) {
			throw new IllegalArgumentException("Unknown table for alias: " + qualifiedTable);
		}
		aliases.put(aliasKey, tableKey);
	}

	/** Remove one alias; no-op when absent. */
	public void unregisterAlias(String alias) {
		if (alias == null || alias.isBlank()) {
			return;
		}
		aliases.remove(normalize(alias));
	}

	/** Clear all registered aliases. */
	public void clearAliases() {
		aliases.clear();
	}

	/** Snapshot of alias → qualified table (immutable). */
	public Map<String, String> aliases() {
		return Map.copyOf(aliases);
	}

	/**
	 * Resolve JOIN / FROM table ref: alias first, then session schema qualification.
	 */
	public String resolve(SqlSession session, String tableRef) {
		if (tableRef == null || tableRef.isBlank()) {
			throw new IllegalArgumentException("table required");
		}
		final String key = normalize(tableRef);
		final String aliased = aliases.get(key);
		if (aliased != null) {
			return aliased;
		}
		// Bare alias without schema prefix already checked; also try last segment.
		final int dot = key.lastIndexOf('.');
		if (dot > 0) {
			final String bare = key.substring(dot + 1);
			final String bareAliased = aliases.get(bare);
			if (bareAliased != null) {
				return bareAliased;
			}
		}
		return tables.resolveTable(session, tableRef);
	}

	public TableStore requireStore(String table) {
		return tables.requireStore(table);
	}

	/**
	 * Resolve alias / session schema then require the bound {@link TableStore}.
	 */
	public TableStore requireStore(SqlSession session, String tableRef) {
		return tables.requireStore(resolve(session, tableRef));
	}

	/**
	 * True when {@code tableRef} is registered as a cross-domain alias (not catalog-only).
	 */
	public boolean isAlias(String tableRef) {
		if (tableRef == null || tableRef.isBlank()) {
			return false;
		}
		return aliases.containsKey(normalize(tableRef));
	}

	public SqlTableResolver tables() {
		return tables;
	}

	private static String normalize(String name) {
		return name.trim().toLowerCase(Locale.ROOT);
	}
}
