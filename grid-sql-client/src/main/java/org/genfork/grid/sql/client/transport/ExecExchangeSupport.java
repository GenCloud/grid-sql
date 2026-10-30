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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import org.genfork.grid.sql.SqlResult;

/**
 * Shared EXEC exchange state machine for Sync and Reactive {@link PendingExchange} implementations.
 * <p>
 * Outcome delivery stays in the caller (CF vs {@code Sinks}); this type owns portal lifecycle,
 * streaming vs DML completion, and cancel routing.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class ExecExchangeSupport {

	private ExecExchangeSupport() {
	}

	/**
	 * Per-exchange mutable state shared by sync and reactive EXEC handlers.
	 */
	public static final class State {
		private final int fetchWindow;
		private final IntConsumer fetchSender;
		private final Runnable cancelSender;
		private final AtomicBoolean done = new AtomicBoolean();
		private final AtomicBoolean resultEmitted = new AtomicBoolean();
		private volatile boolean streaming;
		private volatile FetchPortal portal;

		public State(int fetchWindow, IntConsumer fetchSender, Runnable cancelSender) {
			this.fetchWindow = fetchWindow;
			this.fetchSender = fetchSender;
			this.cancelSender = cancelSender;
		}

		public AtomicBoolean resultEmitted() {
			return resultEmitted;
		}

		/** Best-effort CANCEL when the consumer abandons the exchange. */
		public void cancel() {
			final FetchPortal live = portal;
			if (live != null) {
				live.cancel();
			} else if (cancelSender != null) {
				cancelSender.run();
			}
		}

		public void onRowDesc(List<SqlResult.ColumnMeta> columns, Consumer<TransportOutcome> deliverOnce) {
			streaming = true;
			final FetchPortal created = new FetchPortal(fetchWindow, fetchSender, cancelSender);
			portal = created;
			emitOnce(resultEmitted, deliverOnce, new TransportOutcome.ResultSet(columns, created));
		}

		public void onRowData(Object[] row) {
			if (done.get()) {
				return;
			}
			final FetchPortal live = portal;
			if (live != null) {
				live.offerRow(row);
			}
		}

		/**
		 * @return {@code true} when the exchange is finished and should leave {@code pending}
		 */
		public boolean completeExec(long affected, String tag, Consumer<TransportOutcome> deliverOnce) {
			if (!done.compareAndSet(false, true)) {
				return true;
			}

			if (streaming) {
				final FetchPortal live = portal;
				if (live != null) {
					live.complete();
				}
				return true;
			}

			if (tag != null && TransportOutcome.CANCEL_TAG.equalsIgnoreCase(tag)) {
				emitOnce(resultEmitted, deliverOnce, new TransportOutcome.Dml(0L, TransportOutcome.CANCEL_TAG));
				return true;
			}

			emitOnce(resultEmitted, deliverOnce, new TransportOutcome.Dml(affected, tag));
			return true;
		}

		public void failPortal(Throwable t) {
			done.set(true);
			final FetchPortal live = portal;
			if (live != null) {
				live.fail(t);
			}
		}

		public void emitOutcomeOnce(TransportOutcome value, Consumer<TransportOutcome> deliver) {
			ExecExchangeSupport.emitOnce(resultEmitted, deliver, value);
		}
	}

	static void emitOnce(
			AtomicBoolean resultEmitted,
			Consumer<TransportOutcome> deliver,
			TransportOutcome value
	) {
		if (resultEmitted.compareAndSet(false, true)) {
			deliver.accept(value);
		}
	}
}
