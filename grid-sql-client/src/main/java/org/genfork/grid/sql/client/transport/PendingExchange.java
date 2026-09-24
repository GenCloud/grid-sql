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

import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.client.ServerMeta;

/**
 * In-flight AUTH / SESSION / EXEC / BATCH_EXEC exchange correlated by requestId.
 * <p>
 * Dual completion: Sync / JDBC implementations use {@code CompletableFuture};
 * reactive SPI implementations may use Reactor {@code Sinks}. Shared demux never
 * bridges CF→Mono.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public interface PendingExchange {
	void completeAuth(ServerMeta serverMeta);

	void completeSessionOpen(int sessionId);

	void completeSessionClose();

	void onRowDesc(List<SqlResult.ColumnMeta> columns);

	void onRowData(Object[] row);

	/**
	 * @return {@code true} when the exchange is finished and should leave {@code pending}
	 */
	boolean completeExec(long affected, String tag);

	void fail(Throwable t);
}
