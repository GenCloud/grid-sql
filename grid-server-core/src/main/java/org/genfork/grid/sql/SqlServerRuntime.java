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
package org.genfork.grid.sql;

import com.google.common.annotations.VisibleForTesting;

import org.genfork.grid.catalog.CatalogMetaCache;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.overlay.OverlayStore;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.netty.SqlServer;
import org.genfork.grid.sql.tx.DistForUpdatePeerLockAgent;
import org.genfork.grid.sql.tx.SqlRecordLockManager;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Spring-free SQL server assembly: catalog + engine + optional TCP listen.
 * When {@code dataDir} is set and no external {@link ReplicationCoordinator} is supplied,
 * a local durable ORCHID+OpLog coordinator is started (peer transport off).
 * Clients connect via {@code grid://} / {@code RemoteConnectionFactory} — no in-process factory.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlServerRuntime implements AutoCloseable {
	public static final int DEFAULT_PORT = 15432;
	public static final int DEFAULT_SHARDS = 4;
	public static final String DEFAULT_BIND_HOST = "0.0.0.0";

	private final TableCatalog catalog;
	private final SqlEngine engine;
	private final SqlServer tcp;
	private final ReplicationCoordinator ownedReplication;

	private SqlServerRuntime(
			TableCatalog catalog,
			SqlEngine engine,
			SqlServer tcp,
			ReplicationCoordinator ownedReplication
	) {
		this.catalog = catalog;
		this.engine = engine;
		this.tcp = tcp;
		this.ownedReplication = ownedReplication;
	}

	public static Builder builder() {
		return new Builder();
	}

	public SqlEngine engine() {
		return engine;
	}

	public TableCatalog catalog() {
		return catalog;
	}

	public SqlServer tcpServer() {
		return tcp;
	}

	/**
	 * Owned solo durability coordinator (tests: seal dump / eviction). Null if external replication.
	 */
	@VisibleForTesting
	public ReplicationCoordinator ownedReplication() {
		return ownedReplication;
	}

	public SqlServerRuntime start() {
		if (tcp != null) {
			tcp.start();
		}
		return this;
	}

	@Override
	public void close() {
		if (tcp != null) {
			tcp.close();
		}
		if (ownedReplication != null) {
			ownedReplication.stop();
		}
	}

	public static final class Builder {
		private Path dataDir;
		private int shards = DEFAULT_SHARDS;
		private ReplicationCoordinator replication;
		private boolean listen;
		private String host = DEFAULT_BIND_HOST;
		private int port = DEFAULT_PORT;
		private String user = "";
		private String password = "";
		private boolean durability = true;
		private String hydrateMode = "FULL";
		private int workingSetMaxEntries = 0;
		private boolean adaptiveDiskFirst = true;
		private OverlayStore overlayStore;
		private long autoPinTtlMs;
		private int preparePoolSize = SqlSession.DEFAULT_PREPARE_POOL_SIZE;
		private long lockWaitTimeoutMs = SqlRecordLockManager.DEFAULT_LOCK_WAIT_MS;
		private int catalogMetaCacheSize = CatalogMetaCache.DEFAULT_SIZE;
		private int recursiveCteMaxDepth = SqlEngine.DEFAULT_RECURSIVE_CTE_MAX_DEPTH;
		private String timezone = "UTC";

		public Builder dataDir(Path dataDir) {
			this.dataDir = dataDir;
			return this;
		}

		public Builder preparePoolSize(int preparePoolSize) {
			this.preparePoolSize = preparePoolSize;
			return this;
		}

		public Builder lockWaitTimeoutMs(long lockWaitTimeoutMs) {
			this.lockWaitTimeoutMs = lockWaitTimeoutMs;
			return this;
		}

		public Builder catalogMetaCacheSize(int catalogMetaCacheSize) {
			this.catalogMetaCacheSize = catalogMetaCacheSize;
			return this;
		}

		public Builder recursiveCteMaxDepth(int recursiveCteMaxDepth) {
			this.recursiveCteMaxDepth = recursiveCteMaxDepth;
			return this;
		}

		public Builder timezone(String timezone) {
			this.timezone = timezone == null || timezone.isBlank() ? "UTC" : timezone.trim();
			return this;
		}

		public Builder shards(int shards) {
			this.shards = shards > 0 ? shards : DEFAULT_SHARDS;
			return this;
		}

		public Builder replication(ReplicationCoordinator replication) {
			this.replication = replication;
			return this;
		}

		/** When false, skip auto local durable coordinator even if dataDir is set. */
		public Builder durability(boolean durability) {
			this.durability = durability;
			return this;
		}

		/** FULL = preload domain; LAZY = shard-touch on first access. */
		public Builder hydrateMode(String hydrateMode) {
			this.hydrateMode = hydrateMode == null || hydrateMode.isBlank() ? "FULL" : hydrateMode.trim().toUpperCase();
			return this;
		}

		/** Max RAM working-set entries (0 = unlimited / adaptive default ceiling). */
		public Builder workingSetMaxEntries(int workingSetMaxEntries) {
			this.workingSetMaxEntries = Math.max(0, workingSetMaxEntries);
			return this;
		}

		/** Adaptive disk-first WS / LAZY by heap pressure (default true when durability on). */
		public Builder adaptiveDiskFirst(boolean adaptiveDiskFirst) {
			this.adaptiveDiskFirst = adaptiveDiskFirst;
			return this;
		}

		/** Soft overlay store for PIN/UNPIN + optional auto-pin TTL. */
		public Builder overlay(OverlayStore overlayStore, long autoPinTtlMs) {
			this.overlayStore = overlayStore;
			this.autoPinTtlMs = autoPinTtlMs;
			return this;
		}

		public Builder listen(boolean listen) {
			this.listen = listen;
			return this;
		}

		public Builder bind(String host, int port) {
			this.host = host;
			this.port = port;
			return this;
		}

		public Builder auth(String user, String password) {
			this.user = user == null ? "" : user;
			this.password = password == null ? "" : password;
			return this;
		}

		public SqlServerRuntime build() {
			ReplicationCoordinator owned = null;
			ReplicationCoordinator effective = replication;
			if (effective == null && durability && dataDir != null) {
				final GridConfigurationProperties props = new GridConfigurationProperties();
				props.getDurability().setEnabled(true);
				props.getDurability().setHydrateMode(hydrateMode);
				props.getDurability().setWorkingSetMaxEntries(workingSetMaxEntries);
				props.getDurability().setAdaptiveDiskFirst(adaptiveDiskFirst);
				props.getReplication().setEnabled(false);
				props.getReplication().setNodeId("sql-solo");
				props.getReplication().setClusterId("sql-local");
				props.getReplication().getOpLog().setDataDir(dataDir.resolve("replication").toString());
				props.getReplication().getOpLog().setFsync(false);
				props.getReplication().getTransport().setBindPort(0);
				effective = new ReplicationCoordinator(props);
				effective.start();
				owned = effective;
			}
			if (effective != null && overlayStore != null) {
				effective.setOverlayStore(overlayStore);
			}
			final TableCatalog catalog = new TableCatalog(dataDir, catalogMetaCacheSize);
			final SqlEngine engine = new SqlEngine(catalog, effective, shards, preparePoolSize);
			engine.lockManager().setLockWaitTimeoutMs(lockWaitTimeoutMs);
			if (effective != null) {
				effective.setForUpdateLockManager(engine.lockManager());
				if (effective.isPeerTransportEnabled()) {
					final List<DistForUpdatePeerLockAgent> agents =
							effective.createNettyDistForUpdatePeerLockAgents();
					if (!agents.isEmpty()) {
						engine.setDistForUpdatePeerLockAgents(agents);
					}
				}
			}
			engine.setRecursiveCteMaxDepth(recursiveCteMaxDepth);
			try {
				engine.setDefaultTimezone(ZoneId.of(timezone));
			} catch (RuntimeException ex) {
				engine.setDefaultTimezone(ZoneOffset.UTC);
			}
			engine.setOverlay(overlayStore, autoPinTtlMs);
			engine.recoverPersistedCatalog();
			final SqlServer tcp = listen ? new SqlServer(host, port, engine, user, password, effective) : null;
			return new SqlServerRuntime(catalog, engine, tcp, owned);
		}
	}
}
