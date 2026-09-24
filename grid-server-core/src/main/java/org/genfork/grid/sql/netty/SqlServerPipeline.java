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

import java.util.concurrent.atomic.AtomicReference;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;

/**
 * Server ChannelPipeline: frame codec + EXEC / BATCH_EXEC handler.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlServerPipeline extends ChannelInitializer<SocketChannel> {
	private static final int DEFAULT_MAX_TX_CONTEXTS = 8;

	private final SqlEngine engine;
	private final String user;
	private final String password;
	private final int maxTxContexts;
	private final ReplicationCoordinator replication;
	private final SqlChannelRegistry channelRegistry;
	private final AtomicReference<Boolean> applyLagStaleOverride;

	public SqlServerPipeline(SqlEngine engine, String user, String password) {
		this(engine, user, password, DEFAULT_MAX_TX_CONTEXTS, null, null, null);
	}

	public SqlServerPipeline(SqlEngine engine, String user, String password, int maxTxContexts) {
		this(engine, user, password, maxTxContexts, null, null, null);
	}

	public SqlServerPipeline(
			SqlEngine engine,
			String user,
			String password,
			int maxTxContexts,
			ReplicationCoordinator replication
	) {
		this(engine, user, password, maxTxContexts, replication, null, null);
	}

	SqlServerPipeline(
			SqlEngine engine,
			String user,
			String password,
			int maxTxContexts,
			ReplicationCoordinator replication,
			SqlChannelRegistry channelRegistry
	) {
		this(engine, user, password, maxTxContexts, replication, channelRegistry, null);
	}

	SqlServerPipeline(
			SqlEngine engine,
			String user,
			String password,
			int maxTxContexts,
			ReplicationCoordinator replication,
			SqlChannelRegistry channelRegistry,
			AtomicReference<Boolean> applyLagStaleOverride
	) {
		this.engine = engine;
		this.user = user;
		this.password = password;
		this.maxTxContexts = maxTxContexts;
		this.replication = replication;
		this.channelRegistry = channelRegistry;
		this.applyLagStaleOverride = applyLagStaleOverride;
	}

	@Override
	protected void initChannel(SocketChannel ch) {
		ch.pipeline()
				.addLast(new SqlFrameCodec())
				.addLast(new SqlExecHandler(
						engine,
						user,
						password,
						maxTxContexts,
						replication,
						channelRegistry,
						applyLagStaleOverride
				));
	}
}
