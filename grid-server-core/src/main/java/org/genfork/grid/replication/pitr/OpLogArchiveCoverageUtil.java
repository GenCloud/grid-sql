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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.util.OpLogArchiveUtil;

/**
 * Archive coverage helpers for PITR: segment + append stream ranges and merge for restore.
 * <p>
 * Offline / ops path only — not on Netty EL.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class OpLogArchiveCoverageUtil {
	private static final long EMPTY_SEQ = 0L;

	private OpLogArchiveCoverageUtil() {
	}

	/**
	 * Inclusive seq coverage of an archive source (empty when no ops).
	 *
	 * @param minSeq inclusive first seq (0 when empty)
	 * @param maxSeq inclusive last seq (0 when empty)
	 * @param empty  true when no ops are present
	 */
	public record Coverage(long minSeq, long maxSeq, boolean absent) {
		public static Coverage none() {
			return new Coverage(EMPTY_SEQ, EMPTY_SEQ, true);
		}

		public static Coverage of(long minSeq, long maxSeq) {
			if (minSeq <= EMPTY_SEQ || maxSeq < minSeq) {
				return none();
			}
			return new Coverage(minSeq, maxSeq, false);
		}
	}

	/**
	 * Coverage from local segment manifest when present; otherwise empty (fail-closed).
	 * Missing/corrupt manifest does not silently re-scan the whole archive.
	 */
	public static Coverage segmentCoverage(Path archiveRoot, String domain, int shard) {
		Objects.requireNonNull(archiveRoot, "archiveRoot");
		Objects.requireNonNull(domain, "domain");
		try {
			final OpLogArchiveUtil.ArchiveManifest manifest =
					OpLogArchiveUtil.readManifest(archiveRoot, domain, shard);
			return Coverage.of(manifest.fromSeq(), manifest.toSeq());
		} catch (RuntimeException ex) {
			return Coverage.none();
		}
	}

	/**
	 * Coverage from append stream (cursor max; min inferred from frames when present).
	 */
	public static Coverage streamCoverage(Path streamRoot, String domain, int shard) {
		Objects.requireNonNull(streamRoot, "streamRoot");
		Objects.requireNonNull(domain, "domain");
		final long last = OpLogArchiveStreamer.lastShippedSeq(streamRoot, domain, shard);
		if (last <= EMPTY_SEQ) {
			return Coverage.none();
		}
		final List<ReplicationOp> ops = OpLogArchiveStreamer.readStreamUntil(
				streamRoot, domain, shard, last);
		return coverageOfOps(ops);
	}

	/**
	 * Union of segment and stream coverage (min of mins, max of maxes). Envelope only —
	 * does not prove contiguous seqs; restore uses {@link #mergeOpsForRestore}.
	 */
	public static Coverage mergedCoverage(Coverage segment, Coverage stream) {
		if (segment == null || segment.absent()) {
			return stream == null ? Coverage.none() : stream;
		}
		if (stream == null || stream.absent()) {
			return segment;
		}
		return Coverage.of(
				Math.min(segment.minSeq(), stream.minSeq()),
				Math.max(segment.maxSeq(), stream.maxSeq()));
	}

	/**
	 * Envelope check: {@code [watermark+1 .. untilSeq]} lies inside min/max of coverage.
	 * Does <strong>not</strong> prove contiguous seqs — use {@link #mergeOpsForRestore} for restore.
	 */
	public static boolean coversEnvelope(long watermark, long untilSeq, Coverage coverage) {
		if (untilSeq <= watermark) {
			return true;
		}
		if (coverage == null || coverage.absent()) {
			return false;
		}
		final long needFrom = watermark + 1L;
		return coverage.minSeq() <= needFrom && coverage.maxSeq() >= untilSeq;
	}

	/**
	 * @deprecated use {@link #coversEnvelope}; name historically implied fail-closed continuity.
	 */
	@Deprecated
	public static boolean covers(long watermark, long untilSeq, Coverage coverage) {
		return coversEnvelope(watermark, untilSeq, coverage);
	}

	/**
	 * Merge segment + stream ops for restore: prefer stream frames on duplicate seq,
	 * keep {@code watermark < opSeq <= untilSeq}, fail-closed on gaps or short tail.
	 */
	public static List<ReplicationOp> mergeOpsForRestore(
			List<ReplicationOp> segmentOps,
			List<ReplicationOp> streamOps,
			long watermark,
			long untilSeqInclusive
	) {
		final TreeMap<Long, ReplicationOp> bySeq = new TreeMap<>();
		putAll(bySeq, segmentOps, watermark, untilSeqInclusive);
		putAll(bySeq, streamOps, watermark, untilSeqInclusive);
		if (untilSeqInclusive <= watermark) {
			return List.of();
		}
		final long needFrom = watermark + 1L;
		if (bySeq.isEmpty() || bySeq.firstKey() > needFrom || bySeq.lastKey() < untilSeqInclusive) {
			throw new IllegalStateException(
					"PITR archive coverage incomplete for [" + needFrom + ".." + untilSeqInclusive
							+ "]; have=" + (bySeq.isEmpty() ? "empty" : (bySeq.firstKey() + ".." + bySeq.lastKey())));
		}
		long expect = needFrom;
		final List<ReplicationOp> out = new ArrayList<>(bySeq.size());
		for (ReplicationOp op : bySeq.values()) {
			if (op.opSeq() != expect) {
				throw new IllegalStateException(
						"PITR archive gap at seq=" + expect + " (found " + op.opSeq() + ")");
			}
			out.add(op);
			expect++;
		}
		if (expect - 1L < untilSeqInclusive) {
			throw new IllegalStateException(
					"PITR archive short of until-seq=" + untilSeqInclusive + " last=" + (expect - 1L));
		}
		return List.copyOf(out);
	}

	/**
	 * Load segment + default stream under {@code archiveRoot} and merge for {@code [W+1..T]}.
	 */
	public static List<ReplicationOp> readMergedUntil(
			Path archiveRoot,
			Path streamRootOrNull,
			String domain,
			int shard,
			long watermark,
			long untilSeqInclusive
	) {
		Objects.requireNonNull(archiveRoot, "archiveRoot");
		Objects.requireNonNull(domain, "domain");
		final List<ReplicationOp> segment = OpLogArchiveUtil.readArchiveUntil(
				archiveRoot, domain, shard, untilSeqInclusive);
		final Path streamRoot = streamRootOrNull != null
				? streamRootOrNull
				: OpLogArchiveStreamer.defaultStreamRoot(archiveRoot);
		final List<ReplicationOp> stream = OpLogArchiveStreamer.readStreamUntil(
				streamRoot, domain, shard, untilSeqInclusive);
		return mergeOpsForRestore(segment, stream, watermark, untilSeqInclusive);
	}

	public static Coverage coverageOfOps(List<ReplicationOp> ops) {
		if (ops == null || ops.isEmpty()) {
			return Coverage.none();
		}
		long min = Long.MAX_VALUE;
		long max = Long.MIN_VALUE;
		for (ReplicationOp op : ops) {
			final long seq = op.opSeq();
			if (seq < min) {
				min = seq;
			}
			if (seq > max) {
				max = seq;
			}
		}
		return Coverage.of(min, max);
	}

	private static void putAll(
			TreeMap<Long, ReplicationOp> bySeq,
			List<ReplicationOp> ops,
			long watermark,
			long untilSeqInclusive
	) {
		if (ops == null || ops.isEmpty()) {
			return;
		}
		for (ReplicationOp op : ops) {
			final long seq = op.opSeq();
			if (seq > watermark && seq <= untilSeqInclusive) {
				bySeq.put(seq, op);
			}
		}
	}
}