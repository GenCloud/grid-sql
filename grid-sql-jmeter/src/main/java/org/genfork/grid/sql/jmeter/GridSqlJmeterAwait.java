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
package org.genfork.grid.sql.jmeter;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import reactor.core.publisher.Mono;

/**
 * Shared await helpers for JMeter samplers: Reactor timeout dispose + CF await.
 * <p>
 * Bounds the Mono with {@link Mono#timeout(Duration)} only — do not also
 * {@code future.get(sameTimeout)} or cancel races produce {@code onErrorDropped}
 * {@link TimeoutException} after the sampler already failed the sample.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class GridSqlJmeterAwait {
	public static final String RESPONSE_OK = "200";
	public static final String RESPONSE_ERR = "500";
	public static final String RESPONSE_TIMEOUT = "504";
	/** Server {@code SqlWireErrorCodes.LOCK_WAIT_TIMEOUT}. */
	public static final String RESPONSE_LOCK_WAIT = "423";
	private static final String SQL_ERROR_LOCK_WAIT_PREFIX = "SQL error 10:";
	private static final String SQL_ERROR_LOCK_CANCEL_PREFIX = "SQL error 11:";
	/** Extra wait after Mono.timeout so Reactor completes the CF before get gives up. */
	private static final long AWAIT_SLACK_MS = 1_000L;

	private GridSqlJmeterAwait() {
	}

	/**
	 * Apply Reactor {@link Mono#timeout(Duration)} before {@link Mono#toFuture()} so
	 * dispose cancels the subscription (wire CANCEL), unlike {@code CompletableFuture#orTimeout}.
	 */
	public static <T> CompletableFuture<T> toTimedFuture(Mono<T> mono, Duration timeout) {
		return mono.timeout(timeout).toFuture();
	}

	/**
	 * Block until the timed Mono completes. Prefer Mono.timeout as the sole deadline;
	 * {@code get} only has slack so interrupt / stuck CF still unblocks the sampler thread.
	 */
	public static <T> T awaitOp(CompletableFuture<T> future, Duration timeout)
			throws TimeoutException, InterruptedException, ExecutionException {
		try {
			return future.get(timeout.toMillis() + AWAIT_SLACK_MS, TimeUnit.MILLISECONDS);
		} catch (TimeoutException ex) {
			future.cancel(true);
			throw ex;
		} catch (CancellationException ex) {
			future.cancel(true);
			throw ex;
		} catch (InterruptedException ex) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw ex;
		} catch (ExecutionException ex) {
			// Mono.timeout / server error — CF already terminal; do not cancel (avoids onErrorDropped).
			throw ex;
		}
	}

	/** Map await failures to JMeter response codes. */
	public static String responseCodeFor(Throwable ex) {
		Throwable cur = ex;
		while (cur != null) {
			if (cur instanceof TimeoutException || cur instanceof CancellationException) {
				return RESPONSE_TIMEOUT;
			}
			final String msg = cur.getMessage();
			if (msg != null && (msg.contains(SQL_ERROR_LOCK_WAIT_PREFIX)
					|| msg.contains(SQL_ERROR_LOCK_CANCEL_PREFIX))) {
				return RESPONSE_LOCK_WAIT;
			}
			cur = cur.getCause();
		}
		return RESPONSE_ERR;
	}
}
