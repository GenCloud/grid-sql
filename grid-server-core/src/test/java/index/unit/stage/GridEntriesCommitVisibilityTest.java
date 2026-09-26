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
import org.genfork.grid.threading.ThreadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Write-path: save awaits map commit; no 100ms floor on busy drain.
 *
 * @author: GenCloud
 * @date: 2026/02
 * @since: 1.0
 */
public class GridEntriesCommitVisibilityTest {
	private static final int BATCH_SIZE = 1_000;
	private static final int SAME_KEY_UPSERTS = 2_000;
	private static final long ADD_TIMEOUT_SEC = 2L;
	private static final long BATCH_TIMEOUT_SEC = 30L;
	private static final long AWAIT_IDLE_MS = 5_000L;
	private static final long FAST_COMMIT_MAX_MS = 50L;

	private GridEntriesProcessor processor;
	private GridEntriesWorker worker;

	@BeforeEach
	void setUp() {
		ThreadService.ensureRunning();
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
		processor.add(null, key, val).get(ADD_TIMEOUT_SEC, TimeUnit.SECONDS);
		final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
		assertArrayEquals(val, processor.get(key));
		assertTrue(elapsedMs < FAST_COMMIT_MAX_MS, "expected sub-50ms commit, was " + elapsedMs + "ms");
	}

	@Test
	void batchOfThousandAllCommitted() throws Exception {
		final List<CompletableFuture<Void>> futures = new ArrayList<>(BATCH_SIZE);
		for (int i = 0; i < BATCH_SIZE; i++) {
			final byte[] key = ("k" + i).getBytes(StandardCharsets.UTF_8);
			final byte[] val = ("v" + i).getBytes(StandardCharsets.UTF_8);
			futures.add(processor.add(null, key, val));
		}
		CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
				.get(BATCH_TIMEOUT_SEC, TimeUnit.SECONDS);
		processor.awaitIdle(AWAIT_IDLE_MS);
		assertEquals(BATCH_SIZE, processor.mapSize());
		assertTrue(processor.isIdle(), "queue/staging must be empty after awaitIdle");
	}

	@Test
	void sameKeyUpsertsDrainStaging() throws Exception {
		final byte[] key = "same".getBytes(StandardCharsets.UTF_8);
		final List<CompletableFuture<Void>> futures = new ArrayList<>(SAME_KEY_UPSERTS);
		for (int i = 0; i < SAME_KEY_UPSERTS; i++) {
			final byte[] val = ("v" + i).getBytes(StandardCharsets.UTF_8);
			futures.add(processor.add(null, key, val));
		}
		CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
				.get(BATCH_TIMEOUT_SEC, TimeUnit.SECONDS);
		processor.awaitIdle(AWAIT_IDLE_MS);
		assertEquals(1, processor.mapSize());
		assertTrue(processor.isIdle(), "same-key coalesce must not strand staging");
		assertArrayEquals(("v" + (SAME_KEY_UPSERTS - 1)).getBytes(StandardCharsets.UTF_8), processor.get(key));
	}
}
