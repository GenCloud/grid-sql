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
package org.genfork.grid.replication.flow;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Inflight and domain-gated flow control for replication ship paths.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class ReplicationFlowControl {
	private final AtomicInteger inflight = new AtomicInteger();
	private final int maxInflight;
	private final Set<String> allowedDomains;

	public ReplicationFlowControl(int maxInflight, List<String> domains) {
		this.maxInflight = Math.max(1, maxInflight);
		this.allowedDomains = domains == null || domains.isEmpty()
				? Set.of()
				: new HashSet<>(domains);
	}

	public boolean allowsDomain(String domainType) {
		return allowedDomains.isEmpty() || allowedDomains.contains(domainType);
	}

	public boolean tryAcquire() {
		while (true) {
			final int cur = inflight.get();
			if (cur >= maxInflight) {
				return false;
			}
			if (inflight.compareAndSet(cur, cur + 1)) {
				return true;
			}
		}
	}

	public void release() {
		release(1);
	}

	public void release(int n) {
		inflight.updateAndGet(v -> Math.max(0, v - n));
	}

	public int inflight() {
		return inflight.get();
	}
}
