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
package org.genfork.grid.replication.pitr;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.apply.ReplicaApplier;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.snapshot.SealedBaseBackupUtil;
import org.genfork.grid.replication.snapshot.SnapshotService;
import org.genfork.grid.replication.util.OpLogArchiveUtil;

/**
 * Offline PITR restore: clear dataDir → install base → replay archive until seq T.
 * <p>
 * Uses the same apply path as hydrate ({@link ReplicaApplier} + optional {@link SnapshotService}).
 * Sync I/O on the calling thread — ops CLI only, never Netty EL.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class PitrRestoreMain {
	private static final String ARG_BASE = "--base";
	private static final String ARG_ARCHIVE = "--archive";
	private static final String ARG_DATA_DIR = "--data-dir";
	private static final String ARG_UNTIL_SEQ = "--until-seq";
	private static final String ARG_DOMAIN = "--domain";
	private static final String ARG_SHARD = "--shard";
	private static final String ARG_NODE_ID = "--node-id";
	private static final String ARG_CLUSTER_ID = "--cluster-id";
	private static final String DEFAULT_NODE_ID = "pitr-restore";
	private static final String DEFAULT_CLUSTER_ID = "grid-default";
	private static final String DEFAULT_DC = "dc-a";
	private static final int DEFAULT_SHARD = 0;
	private static final long DEFAULT_SCHEMA_EPOCH = 1L;

	private PitrRestoreMain() {
	}

	/**
	 * CLI entry:
	 * {@code --base <dir> --archive <dir> --data-dir <dir> --until-seq T
	 * --domain <name> [--shard N] [--node-id id] [--cluster-id id]}.
	 */
	public static void main(String[] args) throws Exception {
		Path baseDir = null;
		Path archiveDir = null;
		Path dataDir = null;
		long untilSeq = -1L;
		String domain = null;
		int shard = DEFAULT_SHARD;
		String nodeId = DEFAULT_NODE_ID;
		String clusterId = DEFAULT_CLUSTER_ID;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case ARG_BASE -> baseDir = Path.of(args[++i]);
				case ARG_ARCHIVE -> archiveDir = Path.of(args[++i]);
				case ARG_DATA_DIR -> dataDir = Path.of(args[++i]);
				case ARG_UNTIL_SEQ -> untilSeq = Long.parseLong(args[++i]);
				case ARG_DOMAIN -> domain = args[++i];
				case ARG_SHARD -> shard = Integer.parseInt(args[++i]);
				case ARG_NODE_ID -> nodeId = args[++i];
				case ARG_CLUSTER_ID -> clusterId = args[++i];
				default -> {
				}
			}
		}
		if (baseDir == null || archiveDir == null || dataDir == null || domain == null || untilSeq < 0L) {
			System.err.println("Usage: PitrRestoreMain --base DIR --archive DIR --data-dir DIR"
					+ " --until-seq T --domain NAME [--shard N]");
			System.exit(2);
			return;
		}
		final RestoreResult result = restore(baseDir, archiveDir, dataDir, domain, shard, untilSeq, nodeId, clusterId);
		System.out.println("PITR restore complete: files=" + result.baseFilesInstalled()
				+ " applied=" + result.opsApplied()
				+ " untilSeq=" + untilSeq);
	}

	/**
	 * Programmatic restore used by CLI and IT.
	 */
	public static RestoreResult restore(
			Path baseDir,
			Path archiveDir,
			Path dataDir,
			String domain,
			int shard,
			long untilSeqInclusive,
			String nodeId,
			String clusterId
	) {
		Objects.requireNonNull(baseDir, "baseDir");
		Objects.requireNonNull(archiveDir, "archiveDir");
		Objects.requireNonNull(dataDir, "dataDir");
		Objects.requireNonNull(domain, "domain");
		Objects.requireNonNull(nodeId, "nodeId");
		Objects.requireNonNull(clusterId, "clusterId");

		SealedBaseBackupUtil.clearDataDir(dataDir);
		final int baseFiles = SealedBaseBackupUtil.installBase(baseDir, dataDir);

		final ReplicationNodeState nodeState = new ReplicationNodeState(
				nodeId, clusterId, DEFAULT_DC, DEFAULT_SCHEMA_EPOCH);
		try (OpLog opLog = new OpLog(dataDir, false)) {
			final GridEntriesProcessor processor = new GridEntriesProcessor(
					shard, new GridScalableMap(), null, null);
			final ReplicaApplier applier = new ReplicaApplier(
					nodeState, opLog, s -> s == shard ? processor : null, null, true);

			final List<ReplicationOp> archived = OpLogArchiveUtil.readArchiveUntil(
					archiveDir, domain, shard, untilSeqInclusive);
			int applied = 0;
			for (ReplicationOp op : archived) {
				applier.apply(op, true);
				applied++;
			}
			applier.discardOpenTxStaging();
			return new RestoreResult(baseFiles, applied, processor, nodeState);
		}
	}

	/**
	 * Restore outcome for CLI / tests.
	 *
	 * @param baseFilesInstalled files copied/linked from base
	 * @param opsApplied         archived ops applied through until-seq
	 * @param processor          map processor for the restored shard (IT assertions)
	 * @param nodeState          applied watermarks after restore
	 */
	public record RestoreResult(
			int baseFilesInstalled,
			int opsApplied,
			GridEntriesProcessor processor,
			ReplicationNodeState nodeState
	) {
	}
}
