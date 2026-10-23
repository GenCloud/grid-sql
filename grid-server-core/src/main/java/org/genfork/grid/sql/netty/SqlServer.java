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

import java.net.InetSocketAddress;
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
	/** Child socket: disable Nagle for SQL frame latency. */
	private static final boolean CHILD_TCP_NODELAY = true;
	/** Child socket: detect half-open clients (parity with replication transport). */
	private static final boolean CHILD_SO_KEEPALIVE = true;

	/**
	 * Default concurrent logical sessions per TCP channel when Boot/YAML omit the knob.
	 */
	public static final int DEFAULT_MAX_TX_CONTEXTS = 8;

	/**
	 * Default reader-idle close (ms). {@code <= 0} disables idle close.
	 */
	public static final long DEFAULT_IDLE_TIMEOUT_MS = 0L;

	private final String host;
	/** Listen port; updated from the bound channel when ctor port is {@code 0}. */
	private volatile int port;
	private final SqlEngine engine;
	private final String user;
	private final String password;
	private final int maxTxContexts;
	private final long idleTimeoutMs;
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
	/** Retained after {@link #start()} for option probes (tests). */
	private volatile ServerBootstrap lastBootstrap;

	public SqlServer(String host, int port, SqlEngine engine, String user, String password) {
		this(host, port, engine, user, password, DEFAULT_MAX_TX_CONTEXTS, DEFAULT_IDLE_TIMEOUT_MS, null);
	}

	public SqlServer(String host, int port, SqlEngine engine, String user, String password, int maxTxContexts) {
		this(host, port, engine, user, password, maxTxContexts, DEFAULT_IDLE_TIMEOUT_MS, null);
	}

	public SqlServer(
			String host,
			int port,
			SqlEngine engine,
			String user,
			String password,
			ReplicationCoordinator replication
	) {
		this(host, port, engine, user, password, DEFAULT_MAX_TX_CONTEXTS, DEFAULT_IDLE_TIMEOUT_MS, replication);
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
		this(host, port, engine, user, password, maxTxContexts, DEFAULT_IDLE_TIMEOUT_MS, replication);
	}

	public SqlServer(
			String host,
			int port,
			SqlEngine engine,
			String user,
			String password,
			int maxTxContexts,
			long idleTimeoutMs,
			ReplicationCoordinator replication
	) {
		this.host = host == null ? "0.0.0.0" : host;
		this.port = port;
		this.engine = engine;
		this.user = user;
		this.password = password;
		/* <= 0 = unlimited (same contract as {@link SqlExecHandler}). */
		this.maxTxContexts = maxTxContexts <= 0 ? Integer.MAX_VALUE : maxTxContexts;
		this.idleTimeoutMs = idleTimeoutMs;
		this.replication = replication;
	}

	/**
	 * Effective per-channel session cap ({@link Integer#MAX_VALUE} when unlimited).
	 */
	public int maxTxContexts() {
		return maxTxContexts;
	}

	/**
	 * Reader-idle close timeout in milliseconds ({@code <= 0} = disabled).
	 */
	public long idleTimeoutMs() {
		return idleTimeoutMs;
	}

	/**
	 * Force {@code applyLagStale} on AUTH_OK / ERROR meta for this listen (IT / chaos).
	 * Pass {@code null} to clear the override.
	 */
	@VisibleForTesting
	public void overrideApplyLagStale(Boolean applyLagStale) {
		applyLagStaleOverride.set(applyLagStale);
	}

	/**
	 * Child {@link ChannelOption#SO_KEEPALIVE} after bind (tests).
	 */
	@VisibleForTesting
	Boolean childSoKeepAlive() {
		final ServerBootstrap bootstrap = lastBootstrap;
		if (bootstrap == null) {
			return null;
		}
		final Object value = bootstrap.config().childOptions().get(ChannelOption.SO_KEEPALIVE);
		return value instanceof Boolean keepAlive ? keepAlive : null;
	}

	/**
	 * Child {@link ChannelOption#TCP_NODELAY} after bind (tests).
	 */
	@VisibleForTesting
	Boolean childTcpNoDelay() {
		final ServerBootstrap bootstrap = lastBootstrap;
		if (bootstrap == null) {
			return null;
		}
		final Object value = bootstrap.config().childOptions().get(ChannelOption.TCP_NODELAY);
		return value instanceof Boolean tcpNoDelay ? tcpNoDelay : null;
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
					.childOption(ChannelOption.TCP_NODELAY, CHILD_TCP_NODELAY)
					.childOption(ChannelOption.SO_KEEPALIVE, CHILD_SO_KEEPALIVE)
					.childHandler(new SqlServerPipeline(
							engine,
							user,
							password,
							maxTxContexts,
							idleTimeoutMs,
							replication,
							channelRegistry,
							applyLagStaleOverride
					));
			lastBootstrap = b;
			channel = b.bind(host, port).sync().channel();
			if (channel.localAddress() instanceof InetSocketAddress bound) {
				this.port = bound.getPort();
			}
			log.info("SQL server listening on {}:{}", host, this.port);
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
		lastBootstrap = null;
		started.set(false);
	}
}
