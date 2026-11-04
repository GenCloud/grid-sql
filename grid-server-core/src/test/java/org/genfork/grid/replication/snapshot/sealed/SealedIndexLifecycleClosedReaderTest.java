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
package org.genfork.grid.replication.snapshot.sealed;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.GridCompositeIndex;
import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.mem.index.StrictUniqueUpsertUtil;
import org.genfork.grid.mem.index.btree.GridPointerBPTree;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Package-local repro matrix: closed {@code .sbpt} must not ISE on EQ / STRICT paths.
 * <p>
 * Covers release-without-clear, purge/deleteIndex, and lifecycle util detach-before-close.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class SealedIndexLifecycleClosedReaderTest {
	private static final String DOMAIN = "lifecycle_closed";
	private static final String PROPERTY = "email";
	private static final int SHARD = 0;
	private static final long RACE_SPIN_MS = 50L;
	private static final long RACE_AWAIT_SEC = 10L;

	@TempDir
	Path temporary;

	@Test
	void releaseWithoutClearSearchEqSkipsClosed() throws Exception {
		final byte[] indexKey = bytes("a@x");
		final byte[] rowKey = bytes("row-1");
		final Path file = temporary.resolve("email.sbpt");
		SealedBPTreeWriter.write(file, DOMAIN, SHARD, PROPERTY,
				List.of(new SealedBPTreeWriter.Entry(indexKey, rowKey)));

		final GridPointerBPTree ram = new GridPointerBPTree(PROPERTY + "-STRICT", true);
		try (SealedBPTreeReader reader = SealedBPTreeReader.open(file)) {
			final SealedFallbackIndex fallback = new SealedFallbackIndex(ram);
			fallback.addReader(reader);
			assertTrue(fallback.hasSealedReaders());

			reader.closeForOverwrite();
			assertTrue(reader.isClosed());

			final IndexOperationResult result = assertDoesNotThrow(
					() -> fallback.searchEq(new SingleTreeKey(indexKey)));
			assertEquals(0, result.getSize(), "closed sealed must be pruned; RAM empty");
			assertFalse(fallback.hasSealedReaders(), "closed readers removed from fallback");
		}
	}

	@Test
	void releaseWithoutClearStrictInsertOrReplaceSkipsClosed() throws Exception {
		final byte[] indexKey = bytes("strict@x");
		final byte[] rowKey = bytes("row-s");
		final Path file = temporary.resolve("email-strict.sbpt");
		SealedBPTreeWriter.write(file, DOMAIN, SHARD, PROPERTY,
				List.of(new SealedBPTreeWriter.Entry(indexKey, rowKey)));

		final GridPointerBPTree ram = new GridPointerBPTree(PROPERTY + "-STRICT", true);
		try (SealedBPTreeReader reader = SealedBPTreeReader.open(file)) {
			final SealedFallbackIndex fallback = new SealedFallbackIndex(ram);
			fallback.addReader(reader);
			reader.closeForOverwrite();

			assertDoesNotThrow(() -> StrictUniqueUpsertUtil.insertOrReplaceOwner(
					fallback,
					new SingleTreeKey(indexKey),
					new IndexPointerRef(0, rowKey)));
			assertEquals(1, fallback.searchEq(new SingleTreeKey(indexKey)).getSize());
		}
	}

	@Test
	void detachQuerySealedIndexesThenCloseIsSafe() throws Exception {
		final Path sealedRoot = temporary.resolve("sealed");
		Files.createDirectories(sealedRoot);
		final byte[] indexKey = bytes("d@x");
		final byte[] rowKey = bytes("row-d");
		final TableSchema schema = TableSchema.builder(DOMAIN)
				.primaryKey("id", SqlType.INT)
				.column(PROPERTY, SqlType.VARCHAR)
				.index(IndexDef.of(PROPERTY + "_u", IndexType.STRICT, PROPERTY))
				.build();
		final GridCompositeIndex composite = new GridCompositeIndex(schema);
		final SealedBPTreeService service = new SealedBPTreeService(sealedRoot);
		SealedBPTreeWriter.write(service.indexFile(DOMAIN, SHARD, PROPERTY), DOMAIN, SHARD, PROPERTY,
				List.of(new SealedBPTreeWriter.Entry(indexKey, rowKey)));

		final SealedBPTreeReader reader = service.open(DOMAIN, SHARD, PROPERTY);
		composite.installSealedFallback(PROPERTY, reader);

		SealedIndexLifecycleUtil.detachQuerySealedIndexes(composite);
		final GridEntriesProcessor noProcessor = null;
		SealedIndexLifecycleUtil.detachAndReleaseShard(
				noProcessor, service, DOMAIN, SHARD);

		assertDoesNotThrow(() -> composite.forEachSingleColumnBPTree((name, tree) -> {
			if (PROPERTY.equals(name)) {
				tree.searchEq(new SingleTreeKey(indexKey));
			}
		}));
		composite.cancelDumpStats();
		service.closeAll();
	}

	@Test
	void searchEqAfterClose_softMissEmpty() throws Exception {
		final byte[] indexKey = bytes("soft@x");
		final byte[] rowKey = bytes("row-soft");
		final Path file = temporary.resolve("email-soft.sbpt");
		SealedBPTreeWriter.write(file, DOMAIN, SHARD, PROPERTY,
				List.of(new SealedBPTreeWriter.Entry(indexKey, rowKey)));
		try (SealedBPTreeReader reader = SealedBPTreeReader.open(file)) {
			assertEquals(1, reader.searchEq(indexKey).size());
			reader.closeForOverwrite();
			assertTrue(reader.isClosed());
			assertEquals(List.of(), assertDoesNotThrow(() -> reader.searchEq(indexKey)));
		}
	}

	/**
	 * Race repro for Contended dumpEq: close while EQ probes must soft-miss (no closed ISE).
	 */
	@Test
	void concurrentSearchEqVsClose_skipsClosedWithoutIse() throws Exception {
		final byte[] indexKey = bytes("race@x");
		final byte[] rowKey = bytes("row-race");
		final Path file = temporary.resolve("email-race.sbpt");
		SealedBPTreeWriter.write(file, DOMAIN, SHARD, PROPERTY,
				List.of(new SealedBPTreeWriter.Entry(indexKey, rowKey)));

		final GridPointerBPTree ram = new GridPointerBPTree(PROPERTY + "-STRICT", true);
		final SealedFallbackIndex fallback = new SealedFallbackIndex(ram);
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch stop = new CountDownLatch(1);
		final AtomicReference<Throwable> failure = new AtomicReference<>();
		final ExecutorService pool = Executors.newSingleThreadExecutor();
		try (SealedBPTreeReader reader = SealedBPTreeReader.open(file)) {
			fallback.addReader(reader);
			final Future<?> probe = pool.submit(() -> {
				started.countDown();
				final SingleTreeKey key = new SingleTreeKey(indexKey);
				while (stop.getCount() > 0L) {
					try {
						fallback.searchEq(key);
					} catch (Throwable t) {
						failure.compareAndSet(null, t);
						return;
					}
				}
			});
			assertTrue(started.await(RACE_AWAIT_SEC, TimeUnit.SECONDS));
			Thread.sleep(RACE_SPIN_MS);
			reader.closeForOverwrite();
			Thread.sleep(RACE_SPIN_MS);
			stop.countDown();
			probe.get(RACE_AWAIT_SEC, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
		assertTrue(failure.get() == null, () -> "closed reader raced into query path: " + failure.get());
	}

	@Test
	void deleteIndexClosesServiceReaderSearchEqSkips() throws Exception {
		final Path sealedRoot = temporary.resolve("purge-sealed");
		Files.createDirectories(sealedRoot);
		final byte[] indexKey = bytes("b@y");
		final byte[] rowKey = bytes("row-2");
		final SealedBPTreeService service = new SealedBPTreeService(sealedRoot);
		SealedBPTreeWriter.write(
				service.indexFile(DOMAIN, SHARD, PROPERTY),
				DOMAIN, SHARD, PROPERTY,
				List.of(new SealedBPTreeWriter.Entry(indexKey, rowKey)));
		final GridPointerBPTree ram = new GridPointerBPTree(PROPERTY + "-STRICT", true);
		ram.insert(new SingleTreeKey(indexKey), new IndexPointerRef(0, rowKey));

		final SealedBPTreeReader reader = service.open(DOMAIN, SHARD, PROPERTY);
		final SealedFallbackIndex fallback = new SealedFallbackIndex(ram);
		fallback.addReader(reader);
		assertTrue(fallback.hasSealedReaders());

		service.deleteIndex(DOMAIN, SHARD, PROPERTY);
		assertTrue(reader.isClosed());

		final IndexOperationResult result = assertDoesNotThrow(
				() -> fallback.searchEq(new SingleTreeKey(indexKey)));
		assertEquals(1, result.getSize(), "RAM STRICT hit must survive sealed delete");
		assertFalse(fallback.hasSealedReaders());
		service.closeAll();
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}
}
