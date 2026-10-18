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
package index.unit.net;

import org.genfork.grid.net.NettyClientDisconnectUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Benign disconnect classification for Netty {@code exceptionCaught}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
@DisplayName("NettyClientDisconnectUtil")
class NettyClientDisconnectUtilTest {
	@Test
	@DisplayName("connection_reset_and_closed_channel_are_benign")
	void connectionResetAndClosedChannelAreBenign() {
		assertTrue(NettyClientDisconnectUtil.isBenignDisconnect(
				new SocketException("Connection reset")));
		assertTrue(NettyClientDisconnectUtil.isBenignDisconnect(
				new IOException("Broken pipe")));
		assertTrue(NettyClientDisconnectUtil.isBenignDisconnect(new ClosedChannelException()));
		assertTrue(NettyClientDisconnectUtil.isBenignDisconnect(
				new RuntimeException("wrap", new SocketException("Connection reset by peer"))));
	}

	@Test
	@DisplayName("unrelated_errors_are_not_benign")
	void unrelatedErrorsAreNotBenign() {
		assertFalse(NettyClientDisconnectUtil.isBenignDisconnect(null));
		assertFalse(NettyClientDisconnectUtil.isBenignDisconnect(
				new IllegalStateException("schemaEpoch mismatch")));
		assertFalse(NettyClientDisconnectUtil.isBenignDisconnect(
				new IOException("disk full")));
	}
}
