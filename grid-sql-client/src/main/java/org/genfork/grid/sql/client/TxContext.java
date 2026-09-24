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
package org.genfork.grid.sql.client;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Independent TX multiplexed on a {@link Connection} transport (sessionId is internal).
 * <p>
 * Lifecycle is reactive: use {@link #commit()}, {@link #rollback()}, or {@link #close()}.
 * Never {@code .block()} inside library close paths — that stalls Reactor / virtual carriers.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public interface TxContext {
	Statement createStatement(String sql);

	Mono<Void> commit();

	Mono<Void> rollback();

	/**
	 * Create a named in-TX savepoint (SQL {@code SAVEPOINT name}). In-memory only until
	 * {@link #commit()}; does not touch OpLog/ORCHID. Rejects outside an open TX on the server.
	 */
	Mono<Savepoint> savepoint(String name);

	/**
	 * Roll dirty mutations back to {@code savepoint} ({@code ROLLBACK TO SAVEPOINT}).
	 * Keeps the TX open; sequences are not rewound (PG-like).
	 */
	Mono<Void> rollbackTo(Savepoint savepoint);

	/**
	 * Drop {@code savepoint} ({@code RELEASE SAVEPOINT}) without rolling back.
	 */
	Mono<Void> release(Savepoint savepoint);

	/**
	 * Ends the TX if still open (ROLLBACK) and releases the logical session.
	 * Non-blocking; compose with {@code then}/{@code usingWhen}. Sync edges (CLI/tests)
	 * may {@code .block()} only at the application boundary, never inside this API.
	 */
	Mono<Void> close();

	/**
	 * Opaque prepare/commit handle for concurrent TX on one transport.
	 * Assigned at {@code BEGIN}; abort/retry gets a new handle via a new {@link #begin()}.
	 * Zero when not applicable (autocommit).
	 */
	default long prepareHandle() {
		return 0L;
	}

	default Flux<Result> execute(String sql) {
		return createStatement(sql).execute();
	}

	/**
	 * Execute {@code sqls} in order sharing this open TX (remote: one {@code BATCH_EXEC} RTT).
	 * <p>
	 * All statements see the same dirty buffer until {@link #commit()} / {@link #rollback()}.
	 * Each emitted {@link Result} is one statement outcome; fail-fast aborts the remainder.
	 */
	Flux<Result> executeBatch(List<String> sqls);
}
