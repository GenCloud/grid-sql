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
package org.genfork.grid.sql.netty;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.annotations.VisibleForTesting;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty SQL TCP listen facade. Owns ServerBootstrap / ELG; pipeline is {@link SqlServerPipeline}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlServer implements AutoCloseable {
	private static final Logger log = LoggerFactory.getLogger(SqlServer.class);

	private static final int DEFAULT_MAX_TX_CONTEXTS = 8;

	private final String host;
	private final int port;
	private final SqlEngine engine;
	private final String user;
	private final String password;
	private final int maxTxContexts;
	private final ReplicationCoordinator replication;
	/**
	 * Test hook: when non-null, AUTH/ERROR {@code ServerMeta.applyLagStale} uses this value
	 * (lightweight multi-replica IT without full HA lag).
	 */
	private final AtomicReference<Boolean> applyLagStaleOverride = new AtomicReference<>();
	private final SqlChannelRegistry channelRegistry = new SqlChannelRegistry();
	private final Runnable promotionListener = channelRegistry::pushPromoteNotify;
	private final AtomicBoolean started = new AtomicBoolean();
	private EventLoopGroup boss;
	private EventLoopGroup worker;
	private Channel channel;

	public SqlServer(String host, int port, SqlEngine engine, String user, String password) {
		this(host, port, engine, user, password, DEFAULT_MAX_TX_CONTEXTS, null);
	}

	public SqlServer(String host, int port, SqlEngine engine, String user, String password, int maxTxContexts) {
		this(host, port, engine, user, password, maxTxContexts, null);
	}

	public SqlServer(
			String host,
			int port,
			SqlEngine engine,
			String user,
			String password,
			ReplicationCoordinator replication
	) {
		this(host, port, engine, user, password, DEFAULT_MAX_TX_CONTEXTS, replication);
	}

	public SqlServer(
			String host,
			int port,
			SqlEngine engine,
			String user,
			String password,
			int maxTxContexts,
			ReplicationCoordinator replication
	) {
		this.host = host == null ? "0.0.0.0" : host;
		this.port = port;
		this.engine = engine;
		this.user = user;
		this.password = password;
		this.maxTxContexts = Math.max(1, maxTxContexts);
		this.replication = replication;
	}

	/**
	 * Force {@code applyLagStale} on AUTH_OK / ERROR meta for this listen (IT / chaos).
	 * Pass {@code null} to clear the override.
	 */
	@VisibleForTesting
	public void overrideApplyLagStale(Boolean applyLagStale) {
		applyLagStaleOverride.set(applyLagStale);
	}

	public void start() {
		if (!started.compareAndSet(false, true)) {
			return;
		}
		boss = new NioEventLoopGroup(1, Thread.ofPlatform().name("sql-boss-", 0).factory());
		worker = new NioEventLoopGroup(0, Thread.ofPlatform().name("sql-worker-", 0).factory());
		if (replication != null) {
			replication.addPromotionListener(promotionListener);
		}
		try {
			final ServerBootstrap b = new ServerBootstrap()
					.group(boss, worker)
					.channel(NioServerSocketChannel.class)
					.childOption(ChannelOption.TCP_NODELAY, true)
					.childHandler(new SqlServerPipeline(
							engine,
							user,
							password,
							maxTxContexts,
							replication,
							channelRegistry,
							applyLagStaleOverride
					));
			channel = b.bind(host, port).sync().channel();
			log.info("SQL server listening on {}:{}", host, port);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			close();
			throw new IllegalStateException("SQL server bind interrupted", e);
		} catch (Exception e) {
			close();
			throw new IllegalStateException("SQL server failed to start on " + host + ":" + port, e);
		}
	}

	public int port() {
		return port;
	}

	public String host() {
		return host;
	}

	public SqlEngine engine() {
		return engine;
	}

	/** True when bind completed and the server channel is still active. */
	public boolean isListening() {
		return started.get() && channel != null && channel.isActive();
	}

	@Override
	public void close() {
		if (replication != null) {
			replication.removePromotionListener(promotionListener);
		}
		if (channel != null) {
			channel.close();
			channel = null;
		}
		if (worker != null) {
			worker.shutdownGracefully();
			worker = null;
		}
		if (boss != null) {
			boss.shutdownGracefully();
			boss = null;
		}
		started.set(false);
	}
}