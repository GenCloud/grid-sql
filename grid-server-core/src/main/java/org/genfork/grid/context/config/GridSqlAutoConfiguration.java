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
package org.genfork.grid.context.config;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.context.config.GridConfigurationProperties.SqlProps;
import org.genfork.grid.context.config.GridConfigurationProperties.SqlServerProps;
import org.genfork.grid.overlay.OverlayStore;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlServerRuntime;
import org.genfork.grid.sql.netty.SqlServer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/**
 * Thin Boot adapter: Environment → {@link SqlServerRuntime} (engine + optional TCP).
 * Clients use {@code grid://} / remote {@code ConnectionFactory}; no in-process embed factory.
 * Opt-in replica reads: {@code ConnectionFactory.fromUrl} with {@code readEndpoints} auto-wires
 * routing (writer + READ_REPLICA). Boot does not auto-wire a read pool
 * ({@code replicaReadsEnabled} stays opt-in on the server).
 *
 * @author: GenCloud
 * @date: 2026/07
 * @since: 1.0
 */
@AutoConfiguration
@EnableConfigurationProperties(GridConfigurationProperties.class)
public class GridSqlAutoConfiguration {
	@Bean(destroyMethod = "close")
	@ConditionalOnMissingBean(SqlServerRuntime.class)
	public SqlServerRuntime sqlServerRuntime(
			GridConfigurationProperties properties,
			@Autowired(required = false) ReplicationCoordinator replicationCoordinator,
			ObjectProvider<OverlayStore> overlayStore
	) {
		GridAutoConfiguration.applyDuplex(properties);
		final SqlProps sql = properties.getSql();
		final Path dataDir = sql.getDataDir() == null || sql.getDataDir().isBlank()
				? null
				: Path.of(sql.getDataDir());
		final SqlServerProps listen = properties.getSqlServer();
		final boolean tcp = listen != null && listen.isEnabled();
		final OverlayStore overlay = overlayStore.getIfAvailable();
		final long autoPinTtlMs = properties.getOverlay() == null ? 0L : properties.getOverlay().getAutoPinTtlMs();
		final SqlServerRuntime.Builder b = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(sql.getDefaultShards())
				.preparePoolSize(sql.getPreparePoolSize())
				.lockWaitTimeoutMs(sql.getLockWaitTimeoutMs())
				.catalogMetaCacheSize(sql.getCatalogMetaCacheSize())
				.recursiveCteMaxDepth(sql.getRecursiveCteMaxDepth())
				.timezone(sql.getTimezone())
				.replication(replicationCoordinator)
				.durability(properties.getDurability().isEnabled())
				.hydrateMode(properties.getDurability().getHydrateMode())
				.workingSetMaxEntries(properties.getDurability().getWorkingSetMaxEntries())
				.adaptiveDiskFirst(properties.getDurability().isAdaptiveDiskFirst())
				.overlay(overlay, autoPinTtlMs)
				.listen(tcp);
		if (tcp) {
			b.bind(listen.getHost(), listen.getPort()).auth(listen.getUser(), listen.getPassword());
		}
		return b.build().start();
	}

	@Bean
	@ConditionalOnMissingBean
	public TableCatalog tableCatalog(SqlServerRuntime runtime) {
		return runtime.catalog();
	}

	@Bean
	@ConditionalOnMissingBean
	public SqlEngine sqlEngine(SqlServerRuntime runtime) {
		return runtime.engine();
	}

	@Bean(destroyMethod = "close")
	@ConditionalOnProperty(prefix = "grid.sql-server", name = "enabled", havingValue = "true")
	@ConditionalOnMissingBean
	public SqlServer sqlServer(SqlServerRuntime runtime) {
		final SqlServer tcp = runtime.tcpServer();
		if (tcp == null) {
			throw new IllegalStateException("grid.sql-server.enabled=true but runtime has no TCP listener");
		}
		return tcp;
	}
}
