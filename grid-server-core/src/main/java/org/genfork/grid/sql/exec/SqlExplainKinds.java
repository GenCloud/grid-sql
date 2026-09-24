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
package org.genfork.grid.sql.exec;

/**
 * EXPLAIN plan kind labels (result column {@code kind}).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlExplainKinds {
	public static final String INDEX = "INDEX";
	public static final String JOIN_PK = "JOIN_PK";
	public static final String JOIN_HASH = "JOIN_HASH";
	/** Distributed JOIN: build-side key fan-in + local {@link #JOIN_PK} / {@link #JOIN_HASH}. */
	public static final String JOIN_DIST_FANOUT = "JOIN_DIST_FANOUT";
	/**
	 * EXPLAIN detail marker when {@link #JOIN_DIST_FANOUT} runs inside an open TX:
	 * peer fan-out is committed-only; local dirty overlay stays local until COMMIT.
	 */
	public static final String TX_MODE_COMMITTED_FANOUT = "tx-mode=committed-fanout";
	/** Explicit session opt-in: transaction-scoped peer dirty upserts may join fan-out. */
	public static final String TX_MODE_REMOTE_DIRTY_FANOUT = "tx-mode=remote-dirty-fanout";
	public static final String TABLE = "TABLE";
	/** Adaptive parallel scan (intra-node AQE). */
	public static final String AQE_PARALLEL = "AQE_PARALLEL";
	/** Partitioned MapReduce by domain shard → owner stage. */
	public static final String DIST_MAP = "DIST_MAP";

	private SqlExplainKinds() {
	}
}