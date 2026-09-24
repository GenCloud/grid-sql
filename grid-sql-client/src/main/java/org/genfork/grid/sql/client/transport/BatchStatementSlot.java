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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * Per-statement streaming slot inside a BATCH_EXEC exchange (shared by Sync and reactive).
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class BatchStatementSlot {
	private final FetchPortal portal;

	public BatchStatementSlot(
			int fetchWindow,
			IntConsumer fetchSender,
			Runnable cancelSender,
			AtomicBoolean batchCancelled
	) {
		this.portal = new FetchPortal(fetchWindow, fetchSender, cancelSender, batchCancelled);
	}

	public FetchPortal portal() {
		return portal;
	}
}
