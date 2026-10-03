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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.pitr.OpLogArchiveCoverageUtil;
import org.genfork.grid.replication.pitr.OpLogArchiveStreamer;
import org.genfork.grid.replication.pitr.PitrRestoreMain;
import org.genfork.grid.replication.snapshot.SealedBaseBackupUtil;
import org.genfork.grid.replication.util.OpLogArchiveUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PITR coverage util + multi-seal restore via append stream.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
class OpLogArchiveCoverageUtilTest {
	private static final String DOMAIN = "pitr.Cov";
	private static final int SHARD = 0;
	private static final byte[] KEY = new byte[]{9};
	private static final long SCHEMA_EPOCH = 1L;

	@TempDir
	Path temp;

	@Test
	void coversEmptyAndRange() {
		assertTrue(OpLogArchiveCoverageUtil.coversEnvelope(5L, 5L, OpLogArchiveCoverageUtil.Coverage.none()));
		assertFalse(OpLogArchiveCoverageUtil.coversEnvelope(0L, 3L, OpLogArchiveCoverageUtil.Coverage.none()));
		final OpLogArchiveCoverageUtil.Coverage cov = OpLogArchiveCoverageUtil.Coverage.of(1L, 4L);
		assertTrue(OpLogArchiveCoverageUtil.coversEnvelope(0L, 4L, cov));
		assertFalse(OpLogArchiveCoverageUtil.coversEnvelope(0L, 5L, cov));
		assertTrue(OpLogArchiveCoverageUtil.coversEnvelope(2L, 4L, cov));
	}

	@Test
	void mergeFailsOnGap() {
		final List<ReplicationOp> segment = List.of(upsert(1L, new byte[]{1}), upsert(3L, new byte[]{3}));
		assertThrows(IllegalStateException.class, () ->
				OpLogArchiveCoverageUtil.mergeOpsForRestore(segment, List.of(), 0L, 3L));
	}

	@Test
	void multiSealSegmentReplaceRestoreUsesStream() throws Exception {
		final Path liveDir = temp.resolve("live");
		final Path archiveRoot = temp.resolve("archive");
		final Path streamRoot = OpLogArchiveStreamer.defaultStreamRoot(archiveRoot);
		final Path baseDir = temp.resolve("base");
		final Path restoreDir = temp.resolve("restore");

		try (OpLog live = new OpLog(liveDir, false)) {
			live.append(upsert(1L, new byte[]{10}));
			live.append(upsert(2L, new byte[]{20}));
			live.append(upsert(3L, new byte[]{30}));
			OpLogArchiveStreamer.shipCatchUp(live, streamRoot, DOMAIN, SHARD);
			OpLogArchiveUtil.archiveBeforeTruncate(live, archiveRoot, DOMAIN, SHARD, 2L);
			live.truncateTo(DOMAIN, SHARD, 2L);

			live.append(upsert(4L, new byte[]{40}));
			live.append(upsert(5L, new byte[]{50}));
			OpLogArchiveStreamer.shipCatchUp(live, streamRoot, DOMAIN, SHARD);
			// Segment replace keeps only the latest truncated window.
			OpLogArchiveUtil.archiveBeforeTruncate(live, archiveRoot, DOMAIN, SHARD, 4L);
			live.truncateTo(DOMAIN, SHARD, 4L);

			Files.createDirectories(liveDir.resolve(SealedBaseBackupUtil.SEALED_DIR));
			Files.writeString(liveDir.resolve(SealedBaseBackupUtil.SEALED_DIR).resolve("m.txt"), "b");
			SealedBaseBackupUtil.backupBase(liveDir, baseDir, 0L);
		}

		final OpLogArchiveUtil.ArchiveManifest segmentOnly =
				OpLogArchiveUtil.readManifest(archiveRoot, DOMAIN, SHARD);
		assertEquals(3L, segmentOnly.fromSeq());
		assertEquals(4L, segmentOnly.toSeq());

		final OpLogArchiveCoverageUtil.Coverage streamCov =
				OpLogArchiveCoverageUtil.streamCoverage(streamRoot, DOMAIN, SHARD);
		assertTrue(OpLogArchiveCoverageUtil.coversEnvelope(0L, 5L, streamCov));

		final PitrRestoreMain.RestoreResult restored = PitrRestoreMain.restore(
				baseDir, archiveRoot, streamRoot, restoreDir, DOMAIN, SHARD, 5L, "n", "c");
		assertEquals(5, restored.opsApplied());
		assertArrayEquals(new byte[]{50}, restored.processor().get(KEY));
	}

	private static void assertArrayEquals(byte[] expected, byte[] actual) {
		org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
	}

	private static ReplicationOp upsert(long seq, byte[] value) {
		return OpLogCodec.withChecksum(new ReplicationOp(
				DOMAIN, SHARD, seq, ReplicationOpType.UPSERT, KEY, value, SCHEMA_EPOCH, 0L));
	}
}