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

import org.genfork.grid.catalog.TableAnalyzeStats;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.overlay.OverlayStore;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.tx.DistForUpdatePeerLockAgent;
import org.genfork.grid.store.TableStore;

import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Table name qualification + store bind/lookup for {@link org.genfork.grid.sql.SqlEngine}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlTableResolver {
	/** Default / public schema; catalog stores these tables unqualified. */
	private static final String PUBLIC_SCHEMA = "public";
	private static final char SCHEMA_TABLE_SEP = '.';

	private final TableCatalog catalog;
	private final ReplicationCoordinator replicationCoordinator;
	private final int defaultShards;
	private OverlayStore overlayStore;
	private long autoPinTtlMs;
	private List<Function<String, List<byte[]>>> distributedPeerKeyExecutors = List.of();
	private List<BiFunction<String, byte[], byte[]>> distributedPeerRowBlobFetchers = List.of();
	private List<Function<String, List<byte[]>>> remoteDirtyPeerKeyExecutors = List.of();
	private List<BiFunction<String, byte[], byte[]>> remoteDirtyPeerRowBlobFetchers = List.of();
	/** Peer open-TX delete tombstone key suppliers (PK byte[] only). */
	private List<Function<String, List<byte[]>>> remoteDirtyPeerTombstoneKeyExecutors = List.of();
	/** Peer FOR UPDATE lock agents (Phase 3); empty = local locks only. */
	private List<DistForUpdatePeerLockAgent> distForUpdatePeerLockAgents = List.of();

	public SqlTableResolver(
			TableCatalog catalog,
			ReplicationCoordinator replicationCoordinator,
			int defaultShards
	) {
		this.catalog = catalog;
		this.replicationCoordinator = replicationCoordinator;
		this.defaultShards = defaultShards <= 0 ? 4 : defaultShards;
		catalog.setOnCreate(this::openStore);
		catalog.setOnDrop(this::closeStore);
	}

	/**
	 * Soft overlay + optional auto-pin TTL for stores opened after this call.
	 * Also applied to already-bound stores.
	 */
	public void setOverlay(OverlayStore overlayStore, long autoPinTtlMs) {
		this.overlayStore = overlayStore;
		this.autoPinTtlMs = autoPinTtlMs;
		for (String table : catalog.tableNames()) {
			final TableStore store = catalog.getStore(table);
			if (store != null) {
				store.setOverlay(overlayStore, autoPinTtlMs);
			}
		}
	}


	/** Optional peer key suppliers for distributed SELECT / JOIN via {@link org.genfork.grid.query.distributed.DistributedKeyFanOut}. */
	public void setDistributedPeerKeyExecutors(List<Function<String, List<byte[]>>> peers) {
		this.distributedPeerKeyExecutors = peers == null || peers.isEmpty()
				? List.of()
				: List.copyOf(peers);
	}

	public List<Function<String, List<byte[]>>> distributedPeerKeyExecutors() {
		return distributedPeerKeyExecutors;
	}

	/**
	 * Optional peer row-blob fetchers: {@code (table, keyBytes) → value blob} when local miss.
	 */
	public void setDistributedPeerRowBlobFetchers(List<BiFunction<String, byte[], byte[]>> fetchers) {
		this.distributedPeerRowBlobFetchers = fetchers == null || fetchers.isEmpty()
				? List.of()
				: List.copyOf(fetchers);
	}

	public List<BiFunction<String, byte[], byte[]>> distributedPeerRowBlobFetchers() {
		return distributedPeerRowBlobFetchers;
	}

	/**
	 * Opt-in remote dirty upsert suppliers. They are never consulted unless the session flag is on.
	 */
	public void setRemoteDirtyPeerKeyExecutors(List<Function<String, List<byte[]>>> peers) {
		this.remoteDirtyPeerKeyExecutors = peers == null || peers.isEmpty()
				? List.of()
				: List.copyOf(peers);
	}

	public List<Function<String, List<byte[]>>> remoteDirtyPeerKeyExecutors() {
		return remoteDirtyPeerKeyExecutors;
	}

	public void setRemoteDirtyPeerRowBlobFetchers(List<BiFunction<String, byte[], byte[]>> fetchers) {
		this.remoteDirtyPeerRowBlobFetchers = fetchers == null || fetchers.isEmpty()
				? List.of()
				: List.copyOf(fetchers);
	}

	public List<BiFunction<String, byte[], byte[]>> remoteDirtyPeerRowBlobFetchers() {
		return remoteDirtyPeerRowBlobFetchers;
	}

	/**
	 * Opt-in remote dirty delete tombstone key suppliers (consulted only when session REMOTE_DIRTY).
	 */
	public void setRemoteDirtyPeerTombstoneKeyExecutors(List<Function<String, List<byte[]>>> peers) {
		this.remoteDirtyPeerTombstoneKeyExecutors = peers == null || peers.isEmpty()
				? List.of()
				: List.copyOf(peers);
	}

	public List<Function<String, List<byte[]>>> remoteDirtyPeerTombstoneKeyExecutors() {
		return remoteDirtyPeerTombstoneKeyExecutors;
	}

	/**
	 * Peer row-lock agents for distributed {@code FOR UPDATE} (Phase 3).
	 * Empty list keeps local-only locking (v2 behavior).
	 */
	public void setDistForUpdatePeerLockAgents(List<DistForUpdatePeerLockAgent> agents) {
		this.distForUpdatePeerLockAgents = agents == null || agents.isEmpty()
				? List.of()
				: List.copyOf(agents);
	}

	public List<DistForUpdatePeerLockAgent> distForUpdatePeerLockAgents() {
		return distForUpdatePeerLockAgents;
	}

	public TableCatalog catalog() {
		return catalog;
	}

	public ReplicationCoordinator replication() {
		return replicationCoordinator;
	}

	/**
	 * Normalize a table ref to the catalog key.
	 * <p>
	 * Unqualified names in {@code public} (and {@code public.t}) map to {@code t}.
	 * Non-public schemas keep {@code schema.t}. Matches {@code information_schema}
	 * (reports public + bare name) so DBeaver {@code SELECT … FROM public.t} resolves.
	 */
	public String resolveTable(SqlSession session, String tableRef) {
		if (tableRef == null || tableRef.isBlank()) {
			throw new IllegalArgumentException("table required");
		}
		final String t = tableRef.trim().toLowerCase(Locale.ROOT);
		final int dot = t.indexOf(SCHEMA_TABLE_SEP);
		if (dot > 0) {
			final String schema = t.substring(0, dot);
			final String name = t.substring(dot + 1);
			if (name.isBlank()) {
				throw new IllegalArgumentException("table required");
			}
			if (PUBLIC_SCHEMA.equals(schema)) {
				return name;
			}
			return t;
		}
		final String sch = session == null ? PUBLIC_SCHEMA : session.currentSchema();
		if (PUBLIC_SCHEMA.equals(sch)) {
			return t;
		}
		return sch + SCHEMA_TABLE_SEP + t;
	}

	public TableStore requireStore(String table) {
		TableStore store = catalog.getStore(table);
		if (store == null && table != null) {
			final int dot = table.indexOf(SCHEMA_TABLE_SEP);
			if (dot > 0 && PUBLIC_SCHEMA.equals(table.substring(0, dot))) {
				store = catalog.getStore(table.substring(dot + 1));
			} else if (dot < 0) {
				store = catalog.getStore(PUBLIC_SCHEMA + SCHEMA_TABLE_SEP + table);
			}
		}
		if (store == null) {
			throw new IllegalArgumentException("Unknown table: " + table);
		}
		return store;
	}

	public void ensureStore(TableSchema schema) {
		if (catalog.getStore(schema.tableName()) != null) {
			return;
		}
		openStore(schema.tableName(), schema);
	}

	private void openStore(String tableName, TableSchema schema) {
		try {
			final TableStore store = new TableStore(schema, defaultShards, replicationCoordinator);
			store.setOverlay(overlayStore, autoPinTtlMs);
			catalog.loadAnalyzeStats(tableName);
			final TableAnalyzeStats analyzed = catalog.getAnalyzeStats(tableName);
			if (analyzed != null) {
				store.setAnalyzeStats(analyzed);
			}
			catalog.bindStore(tableName, store);
		} catch (RuntimeException ex) {
			throw new IllegalStateException("Failed to open store for " + tableName + ": " + ex.getMessage(), ex);
		}
	}

	private void closeStore(String tableName, TableSchema schema) {
		final TableStore store = catalog.getStore(tableName);
		if (store != null) {
			store.close();
		}
		// Intentionally no purgeDomainArtifacts here: sealed file delete / hydrate forget on
		// every DROP/CREATE (JMeter capacity stamps) crushed READ_ONLY (~10k vs ~55k). Keep
		// ReplicationCoordinator.purgeDomainArtifacts for explicit retire outside load DROP.
	}
}