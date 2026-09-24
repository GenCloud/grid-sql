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
package org.genfork.grid.replication.crossdc;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for Cross-DC ship lag, failures, and remote ACK latency.
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public class CrossDcMetrics {
	private final AtomicLong lagMs = new AtomicLong();
	private final AtomicLong opsBehind = new AtomicLong();
	private final AtomicLong shipFailures = new AtomicLong();
	private final AtomicLong lastShipEpochMs = new AtomicLong();
	private final AtomicLong remoteAckLatencyMs = new AtomicLong();

	public void recordLag(long lagMsValue, long opsBehindValue) {
		lagMs.set(lagMsValue);
		opsBehind.set(opsBehindValue);
	}

	public void recordShipFailure() {
		shipFailures.incrementAndGet();
	}

	public void recordShipSuccess() {
		lastShipEpochMs.set(System.currentTimeMillis());
	}

	public void recordRemoteAckLatency(long latencyMs) {
		remoteAckLatencyMs.set(latencyMs);
	}

	public long lagMs() {
		return lagMs.get();
	}

	public long opsBehind() {
		return opsBehind.get();
	}

	public long shipFailures() {
		return shipFailures.get();
	}

	public long rpoEstimateMs() {
		return lagMs.get();
	}

	public long remoteAckLatencyMs() {
		return remoteAckLatencyMs.get();
	}
}
