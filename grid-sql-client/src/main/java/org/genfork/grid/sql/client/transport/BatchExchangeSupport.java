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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import org.genfork.grid.sql.SqlResult;

/**
 * Shared BATCH_EXEC exchange logic for Sync and Reactive {@link PendingExchange} implementations.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class BatchExchangeSupport {
	static final String ERR_AUTH = "AUTH unexpected on BATCH_EXEC";
	static final String ERR_SESSION_OPEN = "SESSION_OPEN unexpected on BATCH_EXEC";
	static final String ERR_SESSION_CLOSE = "SESSION_CLOSE unexpected on BATCH_EXEC";

	private BatchExchangeSupport() {
	}

	public static IllegalStateException unexpectedAuth() {
		return new IllegalStateException(ERR_AUTH);
	}

	public static IllegalStateException unexpectedSessionOpen() {
		return new IllegalStateException(ERR_SESSION_OPEN);
	}

	public static IllegalStateException unexpectedSessionClose() {
		return new IllegalStateException(ERR_SESSION_CLOSE);
	}

	/**
	 * Per-batch mutable state shared by sync and reactive BATCH handlers.
	 */
	public static final class State {
		private final int expectedStatements;
		private final int fetchWindow;
		private final IntConsumer fetchSender;
		private final Runnable cancelSender;
		private final AtomicInteger completedStatements = new AtomicInteger();
		private final AtomicBoolean finished = new AtomicBoolean();
		private final AtomicBoolean cancelled = new AtomicBoolean();
		private volatile BatchStatementSlot current;

		public State(
				int expectedStatements,
				int fetchWindow,
				IntConsumer fetchSender,
				Runnable cancelSender
		) {
			this.expectedStatements = Math.max(0, expectedStatements);
			this.fetchWindow = Math.max(1, fetchWindow);
			this.fetchSender = fetchSender;
			this.cancelSender = cancelSender;
		}

		public int expectedStatements() {
			return expectedStatements;
		}

		public AtomicBoolean finished() {
			return finished;
		}

		/** Best-effort CANCEL for the whole batch. */
		public void cancel() {
			if (finished.get()) {
				return;
			}
			if (!cancelled.compareAndSet(false, true)) {
				return;
			}
			if (cancelSender != null) {
				cancelSender.run();
			}
		}

		public void onRowDesc(List<SqlResult.ColumnMeta> columns, Consumer<TransportOutcome> deliver) {
			if (finished.get()) {
				return;
			}
			final BatchStatementSlot slot = new BatchStatementSlot(
					fetchWindow, fetchSender, cancelSender, cancelled);
			current = slot;
			deliver.accept(new TransportOutcome.ResultSet(columns, slot.portal()));
		}

		public void onRowData(Object[] row) {
			final BatchStatementSlot slot = current;
			if (slot == null || finished.get()) {
				return;
			}
			slot.portal().offerRow(row);
		}

		/**
		 * @param deliver emit one statement outcome (queue or sink)
		 * @param finishOk idempotent batch completion hook
		 * @return {@code true} when the exchange is finished and should leave {@code pending}
		 */
		public boolean completeExec(long affected, String tag, Consumer<TransportOutcome> deliver, Runnable finishOk) {
			if (finished.get()) {
				return true;
			}

			if (tag != null && TransportOutcome.CANCEL_TAG.equalsIgnoreCase(tag)) {
				final BatchStatementSlot slot = current;
				if (slot != null) {
					slot.portal().complete();
				}
				finishOk.run();
				return true;
			}

			final BatchStatementSlot slot = current;
			if (slot != null) {
				slot.portal().complete();
				current = null;
			} else {
				current = null;
				deliver.accept(new TransportOutcome.Dml(affected, tag));
			}

			if (completedStatements.incrementAndGet() >= expectedStatements) {
				finishOk.run();
				return true;
			}
			return false;
		}

		/**
		 * @return {@code true} when this call transitioned the batch to failed (first failure)
		 */
		public boolean failPortal(Throwable t) {
			if (!finished.compareAndSet(false, true)) {
				return false;
			}
			final BatchStatementSlot slot = current;
			if (slot != null) {
				slot.portal().fail(t);
			}
			return true;
		}
	}
}
