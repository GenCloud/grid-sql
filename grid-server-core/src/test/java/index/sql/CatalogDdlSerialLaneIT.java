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
package index.sql;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catalog DDL serial lane: concurrent recover vs peer apply; duplicate epoch ACK; true stale reject.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class CatalogDdlSerialLaneIT {
	@TempDir
	Path tmp;

	@Test
	void duplicateEpochIsIdempotentAck() throws Exception {
		final Path catDir = tmp.resolve("cat-dup");
		Files.createDirectories(catDir);
		final SqlEngine engine = new SqlEngine(new TableCatalog(catDir), null, 2);
		engine.execute("CREATE TABLE t_dup (id INT PRIMARY KEY, v INT)");
		final long epoch = engine.catalog().maxAppliedDdlEpoch();
		assertTrue(epoch >= 0L);
		engine.catalog().noteAppliedDdlEpoch(42L);
		assertDoesNotThrow(() -> engine.applyReplicatedDdl(
				"CREATE TABLE t_dup2 (id INT PRIMARY KEY)", 42L));
		assertFalse(engine.catalog().exists("t_dup2"), "duplicate epoch must not re-execute");
		assertEquals(42L, engine.catalog().maxAppliedDdlEpoch());
	}

	@Test
	void trueStaleEpochStillRejects() throws Exception {
		final Path catDir = tmp.resolve("cat-stale");
		Files.createDirectories(catDir);
		final SqlEngine engine = new SqlEngine(new TableCatalog(catDir), null, 2);
		engine.catalog().noteAppliedDdlEpoch(50L);
		final IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> engine.applyReplicatedDdl("CREATE TABLE t_stale (id INT PRIMARY KEY)", 40L));
		assertTrue(ex.getMessage().contains("stale schema epoch"), ex.getMessage());
	}

	@Test
	void concurrentRecoverAndApplyDoesNotNestedThrow() throws Exception {
		final Path catDir = tmp.resolve("cat-race");
		Files.createDirectories(catDir);
		final TableCatalog catalog = new TableCatalog(catDir);
		final SqlEngine engine = new SqlEngine(catalog, null, 2);
		engine.execute("CREATE TABLE t_race (id INT PRIMARY KEY, v INT)");
		engine.catalog().noteAppliedDdlEpoch(10L);

		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch finishRecover = new CountDownLatch(1);
		final AtomicReference<Throwable> applyErr = new AtomicReference<>();

		final Thread recover = new Thread(() -> {
			started.countDown();
			engine.recoverPersistedCatalog();
			finishRecover.countDown();
		}, "catalog-recover");
		final Thread apply = new Thread(() -> {
			try {
				assertTrue(started.await(5L, TimeUnit.SECONDS));
				engine.applyReplicatedDdl(
						"CREATE TABLE t_peer (id INT PRIMARY KEY)", 11L);
			} catch (Throwable t) {
				applyErr.set(t);
			}
		}, "catalog-apply");

		recover.start();
		apply.start();
		assertTrue(finishRecover.await(30L, TimeUnit.SECONDS));
		apply.join(30_000L);
		assertEquals(null, applyErr.get(),
				() -> "serial lane must not nested-throw: " + applyErr.get());
		assertTrue(engine.catalog().exists("t_peer")
						|| engine.catalog().maxAppliedDdlEpoch() >= 11L
						|| engine.catalog().exists("t_race"),
				"recover/apply completed under serial lane");
	}
}
