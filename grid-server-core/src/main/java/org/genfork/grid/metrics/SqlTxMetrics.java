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
package org.genfork.grid.metrics;

import java.util.concurrent.atomic.LongAdder;

/**
 * Process-wide SQL / TX counters for Micrometer binders.
 *
 * @author: GenCloud
 * @date: 2026/07
 * @since: 1.0
 */
public final class SqlTxMetrics {
	private static final LongAdder EXECUTIONS = new LongAdder();
	private static final LongAdder COMMITS = new LongAdder();
	private static final LongAdder ROLLBACKS = new LongAdder();
	private static final LongAdder SESSION_OPENS = new LongAdder();
	private static final LongAdder REPLICA_READS = new LongAdder();
	private static final LongAdder REPLICA_READ_DENIED = new LongAdder();

	private SqlTxMetrics() {
	}

	public static void recordExecution() {
		EXECUTIONS.increment();
	}

	public static void recordCommit() {
		COMMITS.increment();
	}

	public static void recordRollback() {
		ROLLBACKS.increment();
	}

	public static void recordSessionOpen() {
		SESSION_OPENS.increment();
	}

	public static void recordReplicaRead() {
		REPLICA_READS.increment();
	}

	public static void recordReplicaReadDenied() {
		REPLICA_READ_DENIED.increment();
	}

	public static long executions() {
		return EXECUTIONS.sum();
	}

	public static long commits() {
		return COMMITS.sum();
	}

	public static long rollbacks() {
		return ROLLBACKS.sum();
	}

	public static long sessionOpens() {
		return SESSION_OPENS.sum();
	}

	public static long replicaReads() {
		return REPLICA_READS.sum();
	}

	public static long replicaReadDenied() {
		return REPLICA_READ_DENIED.sum();
	}
}
