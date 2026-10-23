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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closes idle SQL channels safely: always before AUTH; after AUTH only when no
 * in-flight EXEC/BATCH and no open RESULT_SET portal (long query / FETCH wait).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class SqlIdleCloseHandler extends ChannelInboundHandlerAdapter {
	private static final Logger log = LoggerFactory.getLogger(SqlIdleCloseHandler.class);

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		if (evt instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
			final SqlExecHandler exec = ctx.pipeline().get(SqlExecHandler.class);
			if (exec == null || exec.mayIdleClose()) {
				if (log.isDebugEnabled()) {
					log.debug("SQL idle close channel={}", ctx.channel().id());
				}

				ctx.close();
				return;
			}
		}
		
		super.userEventTriggered(ctx, evt);
	}
}
