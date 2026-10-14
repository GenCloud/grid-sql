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
package index.unit.replication;

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapReader;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapService;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.genfork.grid.threading.ThreadService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boot race repro: {@code loadAllIntoMap} / forEachLive vs concurrent unpack (HELLO pack).
 * <p>
 * Without per-shard lifecycle gate, unpack {@code releaseReader} closes the pack reader mid-iterate
 * → {@code sealed reader is closed}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class SealedPackVsHydrateRaceIT {
	private static final String DOMAIN = "pack_vs_hydrate";
	private static final int SHARD = 0;
	private static final int PACK_THREADS = 4;
	private static final int RACE_ROUNDS = 8;
	private static final long AWAIT_SEC = 60L;
	private static final byte[] VALUE = "v1".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path tmp;

	@BeforeAll
	static void bootThreads() {
		ThreadService.ensureRunning();
	}

	@AfterAll
	static void shutdownThreads() {
		ThreadService.shutdownNow();
	}

	@Test
	void concurrentLoadAllIntoMapVsUnpack_doesNotCloseUnderIterate() throws Exception {
		final Path sealedRoot = tmp.resolve("sealed");
		GridFs.createDirs(sealedRoot);
		final SealedGridMapService service = new SealedGridMapService(sealedRoot);
		final GridEntriesProcessor processor = new GridEntriesProcessor(
				SHARD, new GridScalableMap(), null, null);
		service.bindProcessor(DOMAIN, shard -> shard == SHARD ? processor : null);

		final byte[] key = "race-key".getBytes(StandardCharsets.UTF_8);
		processor.installCommitted(key, VALUE, false);
		assertTrue(service.dumpDomain(DOMAIN, null, null) > 0);
		final byte[] packed = SealedShardPack.pack(sealedRoot, DOMAIN, SHARD);
		assertTrue(packed.length > 0);

		final ExecutorService logic = ThreadService.getLogicExecutor();
		final AtomicReference<Throwable> failure = new AtomicReference<>();

		for (int round = 0; round < RACE_ROUNDS; round++) {
			final CountDownLatch start = new CountDownLatch(1);
			final List<Future<?>> futures = new ArrayList<>(PACK_THREADS + 1);
			futures.add(logic.submit(() -> {
				awaitStart(start);
				try {
					service.loadAllIntoMap(DOMAIN);
				} catch (Throwable t) {
					failure.compareAndSet(null, t);
				}
			}));
			for (int t = 0; t < PACK_THREADS; t++) {
				futures.add(logic.submit(() -> {
					awaitStart(start);
					try {
						service.unpackShardArtifacts(DOMAIN, SHARD, packed);
						service.openShardLazy(DOMAIN, SHARD);
					} catch (Throwable ex) {
						failure.compareAndSet(null, ex);
					}
				}));
			}
			start.countDown();
			for (Future<?> f : futures) {
				f.get(AWAIT_SEC, TimeUnit.SECONDS);
			}
			assertNull(failure.get(), () -> "race failure: " + failure.get());
		}

		final SealedGridMapReader reader = assertDoesNotThrow(() -> {
			service.openShardLazy(DOMAIN, SHARD);
			return service.reader(DOMAIN, SHARD);
		});
		assertNotNull(reader);
		assertArrayEquals(VALUE, reader.get(key));
		service.closeAll();
	}

	private static void awaitStart(CountDownLatch start) {
		try {
			assertTrue(start.await(AWAIT_SEC, TimeUnit.SECONDS));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
