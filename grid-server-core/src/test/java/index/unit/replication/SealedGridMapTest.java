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

import org.genfork.grid.replication.snapshot.sealed.SealedGridMapReader;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapWriter;
import org.genfork.grid.replication.snapshot.sealed.SealedMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
class SealedGridMapTest {
	@TempDir
	Path temp;

	@Test
	void writesAndReadsViaOpenShard() throws Exception {
		final byte[] keyOne = "key-1".getBytes(StandardCharsets.UTF_8);
		final byte[] valueOne = "value-1".getBytes(StandardCharsets.UTF_8);
		final byte[] keyTwo = "other".getBytes(StandardCharsets.UTF_8);
		final byte[] valueTwo = "value-2".getBytes(StandardCharsets.UTF_8);
		SealedGridMapWriter.writeNodes(temp, "demo", 3, 24L, List.of(
				new SealedGridMapWriter.Kv(keyOne, valueOne),
				new SealedGridMapWriter.Kv(keyTwo, valueTwo)));

		try (SealedGridMapReader reader = SealedGridMapReader.openShard(temp, "demo", 3)) {
			assertEquals(3, reader.shard());
			assertEquals(24L, reader.watermark());
			assertEquals("demo", reader.domainType());
			assertArrayEquals(valueOne, reader.get(keyOne));
			assertArrayEquals(valueTwo, reader.get(keyTwo));
			assertNull(reader.get("missing".getBytes(StandardCharsets.UTF_8)));
			final AtomicInteger live = new AtomicInteger();
			reader.forEachLive((key, value) -> live.incrementAndGet());
			assertEquals(2, live.get());
		}
	}

	@Test
	void writesNodesAndOpensShardFacade() throws Exception {
		final byte[] keyOne = "node-key-1".getBytes(StandardCharsets.UTF_8);
		final byte[] valueOne = "node-value-1".getBytes(StandardCharsets.UTF_8);
		final byte[] keyTwo = "node-key-2".getBytes(StandardCharsets.UTF_8);
		final byte[] valueTwo = "node-value-2".getBytes(StandardCharsets.UTF_8);
		SealedGridMapWriter.writeNodes(temp, "nodes", 7, 91L, List.of(
				new SealedGridMapWriter.Kv(keyOne, valueOne),
				new SealedGridMapWriter.Kv(keyTwo, valueTwo)));

		try (SealedGridMapReader reader = SealedGridMapReader.openShard(temp, "nodes", 7)) {
			assertEquals(7, reader.shard());
			assertEquals(91L, reader.watermark());
			assertEquals("nodes", reader.domainType());
			assertArrayEquals(valueOne, reader.get(keyOne));
			assertArrayEquals(valueTwo, reader.get(keyTwo));
			final AtomicInteger live = new AtomicInteger();
			reader.forEachLive((key, value) -> live.incrementAndGet());
			assertEquals(2, live.get());
		}
		try (Stream<Path> files = Files.list(temp)) {
			assertTrue(files.anyMatch(path -> path.getFileName().toString().contains("_n")));
		}
	}

	@Test
	void tombstoneIsReturnedButNotEnumeratedAsLive() throws Exception {
		final byte[] key = "gone".getBytes(StandardCharsets.UTF_8);
		SealedGridMapWriter.writeNodes(temp, "demo", 1, 11L,
				List.of(new SealedGridMapWriter.Kv(key, new byte[0])));
		try (SealedGridMapReader reader = SealedGridMapReader.openShard(temp, "demo", 1)) {
			assertArrayEquals(new byte[0], reader.get(key));
			final AtomicInteger live = new AtomicInteger();
			reader.forEachLive((foundKey, value) -> live.incrementAndGet());
			assertEquals(0, live.get());
		}
	}

	@Test
	void largePayloadNeverTakesStickyPath() throws Exception {
		final int previousWindow = SealedGridMapReader.WINDOW_BYTES;
		SealedGridMapReader.WINDOW_BYTES = 4 * 1024;
		SealedMetrics.reset();
		try {
			final int valueBytes = (int) (SealedGridMapReader.stickyMaxPayloadBytes() + 1024);
			final byte[] key = "big".getBytes(StandardCharsets.UTF_8);
			final byte[] value = new byte[valueBytes];
			for (int i = 0; i < value.length; i++) {
				value[i] = (byte) (i & 0xff);
			}
			SealedGridMapWriter.writeNodes(temp, "demo", 0, 1L, List.of(new SealedGridMapWriter.Kv(key, value)));
			try (SealedGridMapReader reader = SealedGridMapReader.openShard(temp, "demo", 0)) {
				assertArrayEquals(value, reader.get(key));
				assertFalse(reader.stickyPayloadInstalled());
				assertTrue(SealedMetrics.SEALED_STICKY_REJECTED.get() >= 1L);
				assertTrue(SealedMetrics.SEALED_WINDOW_REMAP.get() >= 1L);
			}
		} finally {
			SealedGridMapReader.WINDOW_BYTES = previousWindow;
		}
	}

	@Test
	void concurrentGetAndCloseDoesNotCrash() throws Exception {
		final List<SealedGridMapWriter.Kv> entries = new ArrayList<>(64);
		for (int i = 0; i < 64; i++) {
			final byte[] key = ("k-" + i).getBytes(StandardCharsets.UTF_8);
			final byte[] value = ("v-" + i).getBytes(StandardCharsets.UTF_8);
			entries.add(new SealedGridMapWriter.Kv(key, value));
		}
		SealedGridMapWriter.writeNodes(temp, "demo", 2, 5L, entries);
		final SealedGridMapReader reader = SealedGridMapReader.openShard(temp, "demo", 2);
		final int workers = 8;
		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(workers);
		final ExecutorService pool = Executors.newFixedThreadPool(workers);
		try {
			for (int w = 0; w < workers; w++) {
				final int worker = w;
				pool.execute(() -> {
					try {
						start.await();
						for (int i = 0; i < 200; i++) {
							try {
								reader.get(("k-" + ((worker + i) % 64)).getBytes(StandardCharsets.UTF_8));
							} catch (IllegalStateException closed) {
								break;
							}
						}
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
					} finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			Thread.sleep(5L);
			reader.close();
			assertTrue(done.await(5L, TimeUnit.SECONDS));
		} finally {
			pool.shutdownNow();
			try {
				reader.close();
			} catch (IllegalStateException | IOException ignored) {
				// already closed
			}
		}
	}
}
