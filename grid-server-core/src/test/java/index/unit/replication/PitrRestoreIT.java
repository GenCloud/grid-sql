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
import org.genfork.grid.replication.pitr.PitrRestoreMain;
import org.genfork.grid.replication.snapshot.SealedBaseBackupUtil;
import org.genfork.grid.replication.util.OpLogArchiveUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PITR v1: archive-before-truncate, base backup, restore-until-seq.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public class PitrRestoreIT {
	private static final String DOMAIN = "pitr.Demo";
	private static final int SHARD = 0;
	private static final byte[] KEY = new byte[]{1};
	private static final long SCHEMA_EPOCH = 1L;

	@TempDir
	Path tempDir;

	@Test
	void archiveTruncateThenRestoreUntilSeq() throws Exception {
		final Path liveDir = tempDir.resolve("live");
		final Path archiveRoot = tempDir.resolve("archive");
		final Path baseDir = tempDir.resolve("base");
		final Path restoreDir = tempDir.resolve("restore");

		try (OpLog live = new OpLog(liveDir, false)) {
			live.append(upsert(1L, new byte[]{10}));
			live.append(upsert(2L, new byte[]{20}));
			live.append(upsert(3L, new byte[]{30}));
			assertEquals(3, live.size(DOMAIN, SHARD));

			final OpLogArchiveUtil.ArchiveManifest manifest = OpLogArchiveUtil.archiveRange(
					live, archiveRoot, DOMAIN, SHARD, 1L, 2L);
			assertEquals(DOMAIN, manifest.domain());
			assertEquals(1L, manifest.fromSeq());
			assertEquals(2L, manifest.toSeq());
			assertEquals(SCHEMA_EPOCH, manifest.schemaEpoch());
			assertTrue(manifest.checksum() != 0L);

			Files.createDirectories(liveDir.resolve(SealedBaseBackupUtil.SEALED_DIR));
			Files.writeString(
					liveDir.resolve(SealedBaseBackupUtil.SEALED_DIR).resolve("marker.txt"),
					"sealed-base");
			Files.createDirectories(liveDir.resolve(SealedBaseBackupUtil.ORCHID_DIR));
			Files.write(liveDir.resolve(SealedBaseBackupUtil.ORCHID_DIR).resolve("state.bin"), new byte[]{1});
			Files.createDirectories(liveDir.resolve(SealedBaseBackupUtil.INDEX_CKPT_DIR));
			Files.writeString(
					liveDir.resolve(SealedBaseBackupUtil.INDEX_CKPT_DIR).resolve("ckpt.meta"),
					"wm=2");

			final int backed = SealedBaseBackupUtil.backupBase(liveDir, baseDir, 2L);
			assertTrue(backed >= 2);

			OpLogArchiveUtil.archiveBeforeTruncate(live, archiveRoot, DOMAIN, SHARD, 2L);
			live.truncateTo(DOMAIN, SHARD, 2L);
			assertEquals(1, live.size(DOMAIN, SHARD));
			assertEquals(3L, live.lastSeq(DOMAIN, SHARD));
			assertEquals(2L, live.truncatedThrough(DOMAIN, SHARD));
		}

		final PitrRestoreMain.RestoreResult restored = PitrRestoreMain.restore(
				baseDir,
				archiveRoot,
				restoreDir,
				DOMAIN,
				SHARD,
				2L,
				"pitr-it",
				"pitr-cluster"
		);
		assertTrue(restored.baseFilesInstalled() >= 2);
		assertEquals(2, restored.opsApplied());
		assertArrayEquals(new byte[]{20}, restored.processor().get(KEY));
		assertEquals(2L, restored.nodeState().appliedWatermark(DOMAIN, SHARD));
		assertTrue(Files.isRegularFile(
				restoreDir.resolve(SealedBaseBackupUtil.ORCHID_DIR).resolve("state.bin")));
	}

	@Test
	void archiveBeforeTruncateFailClosed() throws Exception {
		final Path liveDir = tempDir.resolve("fail-live");
		final Path badArchive = tempDir.resolve("not-a-dir");
		Files.writeString(badArchive, "file-not-dir");

		try (OpLog live = new OpLog(liveDir, false)) {
			live.append(upsert(1L, new byte[]{1}));
			assertThrows(IllegalStateException.class, () ->
					OpLogArchiveUtil.archiveBeforeTruncate(live, badArchive, DOMAIN, SHARD, 1L));
			assertEquals(1, live.size(DOMAIN, SHARD));
			assertEquals(0L, live.truncatedThrough(DOMAIN, SHARD));
		}
	}

	@Test
	void readArchiveUntilFiltersSeq() throws Exception {
		final Path liveDir = tempDir.resolve("filter-live");
		final Path archiveRoot = tempDir.resolve("filter-archive");
		try (OpLog live = new OpLog(liveDir, false)) {
			live.append(upsert(1L, new byte[]{1}));
			live.append(upsert(2L, new byte[]{2}));
			live.append(upsert(3L, new byte[]{3}));
			OpLogArchiveUtil.archiveRange(live, archiveRoot, DOMAIN, SHARD, 1L, 3L);
		}
		final List<ReplicationOp> untilTwo = OpLogArchiveUtil.readArchiveUntil(
				archiveRoot, DOMAIN, SHARD, 2L);
		assertEquals(2, untilTwo.size());
		assertEquals(2L, untilTwo.getLast().opSeq());
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
