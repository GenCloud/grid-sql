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
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import reactor.core.publisher.Mono;

import org.genfork.grid.sql.client.ConnectionOptions;
import org.genfork.grid.sql.client.HostEndpoint;

/**
 * Connect / AUTH helper methods for {@link org.genfork.grid.sql.client.RemoteConnectionFactory}.
 * <p>
 * CAS/atomics and pending correlator stay on the factory; this type owns pure connect
 * timeout, retry classification, and hint ordinal parsing.
 * <p>
 * Reactive {@link #connectOnce} is subscribe-driven ({@link Mono#create}); Sync uses
 * {@link #connectOnceStage} (write-immediate CompletionStage).
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class RemoteConnectSupport {
	private static final String AUTH_MARKER = "auth";
	private static final int EXPONENTIAL_SHIFT_CAP = 16;

	private RemoteConnectSupport() {
	}

	/** Auth failures are not retried across endpoints. */
	public static boolean isNonRetryable(Throwable err) {
		Throwable cur = err;
		while (cur != null) {
			final String msg = cur.getMessage();
			if (msg != null && msg.toLowerCase(Locale.ROOT).contains(AUTH_MARKER)) {
				return true;
			}
			cur = cur.getCause();
		}
		return false;
	}

	/** Trailing decimal ordinal in a host/hint string ({@code -1} when absent). */
	public static int trailingOrdinal(String value) {
		int start = value.length();
		while (start > 0 && Character.isDigit(value.charAt(start - 1))) {
			start--;
		}
		if (start == value.length()) {
			return -1;
		}
		try {
			return Integer.parseInt(value.substring(start));
		} catch (NumberFormatException ignored) {
			return -1;
		}
	}

	/**
	 * One TCP connect with optional {@code connectTimeout} — subscribe-driven {@link Mono#create}.
	 */
	public static Mono<Channel> connectOnce(Bootstrap bootstrap, HostEndpoint ep, Duration connectTimeout) {
		Mono<Channel> connect = Mono.create(sink ->
				bootstrap.connect(ep.host(), ep.port()).addListener((ChannelFutureListener) future -> {
					if (!future.isSuccess()) {
						sink.error(future.cause() != null ? future.cause()
								: new IllegalStateException("connect failed to " + ep));
						return;
					}
					sink.success(future.channel());
				}));
		if (connectTimeout != null && !connectTimeout.isZero() && !connectTimeout.isNegative()) {
			connect = connect.timeout(connectTimeout);
		}
		return connect;
	}

	/** CompletionStage form of connect for Sync / factory {@code obtainStage}. */
	public static CompletionStage<Channel> connectOnceStage(
			Bootstrap bootstrap,
			HostEndpoint ep,
			Duration connectTimeout
	) {
		final CompletableFuture<Channel> future = new CompletableFuture<>();
		bootstrap.connect(ep.host(), ep.port()).addListener((ChannelFutureListener) chFuture -> {
			if (!chFuture.isSuccess()) {
				future.completeExceptionally(chFuture.cause() != null ? chFuture.cause()
						: new IllegalStateException("connect failed to " + ep));
				return;
			}
			future.complete(chFuture.channel());
		});
		if (connectTimeout != null && !connectTimeout.isZero() && !connectTimeout.isNegative()) {
			return future.orTimeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		return future;
	}

	/** Retry backoff from {@link ConnectionOptions#retryMode()}. */
	public static Duration backoffDelay(ConnectionOptions options, int attempt) {
		final Duration base = options.retryDelay();
		return switch (options.retryMode()) {
			case OFF -> Duration.ZERO;
			case FIXED -> base;
			case EXPONENTIAL -> {
				final int shift = Math.min(attempt, EXPONENTIAL_SHIFT_CAP);
				yield base.multipliedBy(1L << shift);
			}
		};
	}
}
