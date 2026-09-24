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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.pitr.OpLogArchiveStreamer;
import org.genfork.grid.replication.pitr.PitrActiveFence;
import org.genfork.grid.replication.pitr.PitrCoordinatedRestore;
import org.genfork.grid.replication.pitr.PitrRestoreMain;
import org.genfork.grid.replication.region.RegionRole;
import org.genfork.grid.replication.snapshot.SealedBaseBackupUtil;
import org.genfork.grid.replication.util.OpLogArchiveUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PITR v2: append-only archive stream + Active fence reject.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public class PitrStreamArchiveIT {
	private static final String DOMAIN = "pitr.Stream";
	private static final int SHARD = 0;
	private static final byte[] KEY = new byte[]{7};
	private static final long SCHEMA_EPOCH = 1L;

	@TempDir
	Path tempDir;

	@Test
	void streamShipCatchUpIsAppendOnly() throws Exception {
		final Path liveDir = tempDir.resolve("live");
		final Path streamRoot = tempDir.resolve("stream");
		try (OpLog live = new OpLog(liveDir, false)) {
			live.append(upsert(1L, new byte[]{10}));
			live.append(upsert(2L, new byte[]{20}));
			assertEquals(2, OpLogArchiveStreamer.shipCatchUp(live, streamRoot, DOMAIN, SHARD));
			assertEquals(2L, OpLogArchiveStreamer.lastShippedSeq(streamRoot, DOMAIN, SHARD));
			assertEquals(0, OpLogArchiveStreamer.shipCatchUp(live, streamRoot, DOMAIN, SHARD));

			live.append(upsert(3L, new byte[]{30}));
			assertEquals(1, OpLogArchiveStreamer.shipCatchUp(live, streamRoot, DOMAIN, SHARD));
			assertEquals(3L, OpLogArchiveStreamer.lastShippedSeq(streamRoot, DOMAIN, SHARD));
		}
		final List<ReplicationOp> untilTwo = OpLogArchiveStreamer.readStreamUntil(
				streamRoot, DOMAIN, SHARD, 2L);
		assertEquals(2, untilTwo.size());
		assertEquals(2L, untilTwo.getLast().opSeq());
		final List<ReplicationOp> all = OpLogArchiveStreamer.readStreamUntil(
				streamRoot, DOMAIN, SHARD, 3L);
		assertEquals(3, all.size());
	}

	@Test
	void activeFenceRejectsHoldAndDualWriter() {
		assertThrows(IllegalStateException.class,
				() -> PitrActiveFence.requireActiveFence(RegionRole.HOLD, false));
		assertThrows(IllegalStateException.class,
				() -> PitrActiveFence.requireActiveFence(RegionRole.ACTIVE, true));
		PitrActiveFence.requireActiveFence(RegionRole.ACTIVE, false);
		assertTrue(PitrActiveFence.isActiveWire(RegionRole.ACTIVE.wireCode()));
	}

	@Test
	void coordinatedRestoreRejectsNonActiveAndDualWriter() throws Exception {
		final Path baseDir = tempDir.resolve("base");
		final Path archiveDir = tempDir.resolve("archive");
		final Path restoreDir = tempDir.resolve("restore");
		Files.createDirectories(baseDir);
		assertThrows(IllegalStateException.class, () ->
				PitrCoordinatedRestore.restoreUnderActiveFence(
						RegionRole.HOLD,
						false,
						baseDir,
						archiveDir,
						restoreDir,
						DOMAIN,
						SHARD,
						1L,
						"pitr-it",
						"pitr-cluster"));
		assertThrows(IllegalStateException.class, () ->
				PitrCoordinatedRestore.restoreUnderActiveFence(
						RegionRole.ACTIVE,
						true,
						baseDir,
						archiveDir,
						restoreDir,
						DOMAIN,
						SHARD,
						1L,
						"pitr-it",
						"pitr-cluster"));
	}

	@Test
	void coordinatedRestoreUnderActiveAppliesArchive() throws Exception {
		final Path liveDir = tempDir.resolve("coord-live");
		final Path archiveRoot = tempDir.resolve("coord-archive");
		final Path baseDir = tempDir.resolve("coord-base");
		final Path restoreDir = tempDir.resolve("coord-restore");
		try (OpLog live = new OpLog(liveDir, false)) {
			live.append(upsert(1L, new byte[]{11}));
			OpLogArchiveUtil.archiveRange(live, archiveRoot, DOMAIN, SHARD, 1L, 1L);
			Files.createDirectories(liveDir.resolve(SealedBaseBackupUtil.SEALED_DIR));
			Files.writeString(
					liveDir.resolve(SealedBaseBackupUtil.SEALED_DIR).resolve("marker.txt"),
					"base");
			SealedBaseBackupUtil.backupBase(liveDir, baseDir, 1L);
		}
		final PitrRestoreMain.RestoreResult result = PitrCoordinatedRestore.restoreUnderActiveFence(
				RegionRole.ACTIVE,
				false,
				baseDir,
				archiveRoot,
				restoreDir,
				DOMAIN,
				SHARD,
				1L,
				"pitr-it",
				"pitr-cluster");
		assertTrue(result.baseFilesInstalled() >= 1);
		assertEquals(1, result.opsApplied());
		assertArrayEquals(new byte[]{11}, result.processor().get(KEY));
	}

	private static ReplicationOp upsert(long seq, byte[] value) {
		return OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN,
				SHARD,
				seq,
				ReplicationOpType.UPSERT,
				KEY,
				value,
				SCHEMA_EPOCH,
				0L
		));
	}
}
