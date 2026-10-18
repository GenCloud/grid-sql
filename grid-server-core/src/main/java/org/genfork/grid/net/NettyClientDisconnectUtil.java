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
package org.genfork.grid.net;

import java.io.IOException;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.util.Locale;

/**
 * Classify benign peer / client disconnects for Netty {@code exceptionCaught}.
 * <p>
 * Abrupt close ({@code Connection reset}, broken pipe, closed channel) is expected when
 * clients drop TCP; handlers should close quietly without WARN flood.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public final class NettyClientDisconnectUtil {
	private static final String TOKEN_CONNECTION_RESET = "connection reset";
	private static final String TOKEN_BROKEN_PIPE = "broken pipe";
	private static final String TOKEN_CONNECTION_ABORT = "connection abort";
	private static final String TOKEN_FORCIBLY_CLOSED = "forcibly closed";
	private static final String TOKEN_REMOTE_HOST_CLOSED = "remote host closed";

	private NettyClientDisconnectUtil() {
	}

	/**
	 * Whether {@code cause} (or a cause in its chain) is a routine disconnect, not a server bug.
	 */
	public static boolean isBenignDisconnect(Throwable cause) {
		Throwable cursor = cause;
		while (cursor != null) {
			if (cursor instanceof ClosedChannelException) {
				return true;
			}
			if (cursor instanceof SocketException || cursor instanceof IOException) {
				if (messageLooksLikeDisconnect(cursor.getMessage())) {
					return true;
				}
			}
			cursor = cursor.getCause();
		}
		return false;
	}

	private static boolean messageLooksLikeDisconnect(String message) {
		if (message == null || message.isBlank()) {
			return false;
		}
		final String lower = message.toLowerCase(Locale.ROOT);
		return lower.contains(TOKEN_CONNECTION_RESET)
				|| lower.contains(TOKEN_BROKEN_PIPE)
				|| lower.contains(TOKEN_CONNECTION_ABORT)
				|| lower.contains(TOKEN_FORCIBLY_CLOSED)
				|| lower.contains(TOKEN_REMOTE_HOST_CLOSED);
	}
}
