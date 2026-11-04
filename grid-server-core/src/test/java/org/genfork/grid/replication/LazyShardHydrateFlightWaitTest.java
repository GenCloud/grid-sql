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
package org.genfork.grid.replication;

import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.replication.util.OpLogStreamKeyUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Package-local: loser of LAZY {@code ensureShardHydrated} parks on an in-flight future
 * (same map the winner publishes) — no product test hooks.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class LazyShardHydrateFlightWaitTest {
	private static final String DOMAIN = "lazy_flight_wait";
	private static final int SHARD = 0;
	private static final long AWAIT_SECONDS = 30L;

	@TempDir
	Path tmp;

	@Test
	void loserAwaitsPublishedFlightUntilComplete() throws Exception {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("repl").toString());
		props.getReplication().setClusterId("c1");
		props.getReplication().setNodeId("n1");

		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		try {
			final SealedHydrateService hydrate = coord.sealedHydrateService();
			assertNotNull(hydrate);
			final String key = OpLogStreamKeyUtil.format(DOMAIN, SHARD);
			final CompletableFuture<Void> stuck = new CompletableFuture<>();
			hydrate.lazyShardHydrateFlights.put(key, stuck);

			final CountDownLatch started = new CountDownLatch(1);
			final AtomicBoolean finished = new AtomicBoolean(false);
			final Thread waiter = new Thread(() -> {
				started.countDown();
				coord.ensureShardHydrated(DOMAIN, SHARD);
				finished.set(true);
			}, "lazy-flight-waiter");
			waiter.start();

			assertTrue(started.await(AWAIT_SECONDS, TimeUnit.SECONDS));
			assertFalse(finished.get(), "loser must park on in-flight hydrate");
			waiter.join(200L);
			assertFalse(finished.get(), "waiter must still be blocked before flight completes");

			stuck.complete(null);
			waiter.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
			assertTrue(finished.get(), "loser must resume after flight completes");
			assertFalse(hydrate.hydratedShards.contains(key),
					"await-only path must not mark shard hydrated");
		} finally {
			coord.stop();
		}
	}
}
