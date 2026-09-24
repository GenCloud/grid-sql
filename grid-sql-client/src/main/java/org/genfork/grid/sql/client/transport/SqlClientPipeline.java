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
package org.genfork.grid.sql.client.transport;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;

import org.genfork.grid.sql.netty.SqlFrameCodec;

/**
 * Client ChannelPipeline: optional read/write timeouts + frame codec + inbound handler.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class SqlClientPipeline extends ChannelInitializer<SocketChannel> {
	private final SqlClientInboundHandler inboundHandler;
	private final Duration readTimeout;
	private final Duration writeTimeout;

	public SqlClientPipeline(SqlClientInboundHandler inboundHandler) {
		this(inboundHandler, Duration.ZERO, Duration.ZERO);
	}

	public SqlClientPipeline(
			SqlClientInboundHandler inboundHandler,
			Duration readTimeout,
			Duration writeTimeout
	) {
		this.inboundHandler = inboundHandler;
		this.readTimeout = readTimeout == null ? Duration.ZERO : readTimeout;
		this.writeTimeout = writeTimeout == null ? Duration.ZERO : writeTimeout;
	}

	@Override
	protected void initChannel(SocketChannel ch) {
		if (!readTimeout.isZero() && !readTimeout.isNegative()) {
			ch.pipeline().addLast(new ReadTimeoutHandler(readTimeout.toMillis(), TimeUnit.MILLISECONDS));
		}
		if (!writeTimeout.isZero() && !writeTimeout.isNegative()) {
			ch.pipeline().addLast(new WriteTimeoutHandler(writeTimeout.toMillis(), TimeUnit.MILLISECONDS));
		}
		ch.pipeline()
				.addLast(new SqlFrameCodec())
				.addLast(inboundHandler);
	}
}
