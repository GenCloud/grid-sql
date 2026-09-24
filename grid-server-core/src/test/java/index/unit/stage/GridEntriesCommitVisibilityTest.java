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
package index.unit.stage;

import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.mem.stage.GridEntriesWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Write-path: save awaits map commit; no 100ms floor on busy drain.
 *
 * @author: GenCloud
 * @date: 2026/02
 * @since: 1.0
 */
public class GridEntriesCommitVisibilityTest {
	private GridEntriesProcessor processor;
	private GridEntriesWorker worker;

	@BeforeEach
	void setUp() {
		processor = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		worker = new GridEntriesWorker(processor);
		worker.start();
	}

	@AfterEach
	void tearDown() {
		worker.stop();
		processor.destroy();
	}

	@Test
	void addCompletesWhenVisibleInMap() throws Exception {
		final byte[] key = "k1".getBytes(StandardCharsets.UTF_8);
		final byte[] val = "v1".getBytes(StandardCharsets.UTF_8);
		final long t0 = System.nanoTime();
		processor.add(null, key, val).get(2, TimeUnit.SECONDS);
		final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
		assertArrayEquals(val, processor.get(key));
		assertTrue(elapsedMs < 50, "expected sub-50ms commit, was " + elapsedMs + "ms");
	}

	@Test
	void batchOfThousandAllCommitted() throws Exception {
		final int n = 1_000;
		final var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>(n);
		for (int i = 0; i < n; i++) {
			final byte[] key = ("k" + i).getBytes(StandardCharsets.UTF_8);
			final byte[] val = ("v" + i).getBytes(StandardCharsets.UTF_8);
			futures.add(processor.add(null, key, val));
		}
		java.util.concurrent.CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
				.get(30, TimeUnit.SECONDS);
		processor.awaitIdle(5_000);
		assertEquals(n, processor.mapSize());
	}
}
