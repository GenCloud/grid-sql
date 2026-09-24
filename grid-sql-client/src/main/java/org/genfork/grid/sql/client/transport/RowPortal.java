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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;

/**
 * Streaming row portal for a RESULT_SET exchange (FETCH demand + buffer, no Reactor).
 * <p>
 * Sync callers use {@link #poll()} / {@link #take(Duration)}; reactive adapters attach a
 * {@link RowListener} and drive {@link #request(long)} from subscriber demand.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public interface RowPortal {
	/**
	 * Request up to {@code demand} more rows (triggers wire FETCH windows).
	 */
	void request(long demand);

	/**
	 * Non-blocking: next buffered row, or {@code null} when empty / not yet complete.
	 */
	Object[] poll();

	/**
	 * Blocking take with timeout. Returns {@code null} when the portal completed normally
	 * with no further rows. Throws on error or timeout.
	 */
	Object[] take(Duration timeout) throws InterruptedException, TimeoutException;

	/**
	 * Best-effort CANCEL of the in-flight EXEC / FETCH portal.
	 */
	void cancel();

	/**
	 * Completes when the portal finishes (normal or exceptionally).
	 */
	CompletionStage<Void> completion();

	/**
	 * {@code true} after normal complete or fail.
	 */
	boolean isDone();

	/**
	 * Failure cause when completed exceptionally; otherwise {@code null}.
	 */
	Throwable error();

	/**
	 * Push listener for reactive adapters (at most one). Drains already-buffered rows.
	 */
	void attach(RowListener listener);

	/**
	 * Push callbacks for row delivery (reactive path).
	 *
	 * @author: GenCloud
	 * @date: 2026/08
	 * @since: 1.0
	 */
	interface RowListener {
		void onRow(Object[] row);

		void onComplete();

		void onError(Throwable error);
	}
}
