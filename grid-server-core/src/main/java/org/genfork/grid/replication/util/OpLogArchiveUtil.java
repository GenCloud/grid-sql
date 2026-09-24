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
package org.genfork.grid.replication.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.google.common.annotations.VisibleForTesting;

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.OpLogSegment;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;

/**
 * Offline OpLog archive helpers for PITR (copy ranges before truncate).
 * <p>
 * Sync I/O runs on the calling thread. Seal / {@code truncateSafe} paths must already
 * be off Netty event-loop (and must not pin VT carriers for long work).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class OpLogArchiveUtil {
	/** Batch size when draining OpLog for archive. */
	private static final int READ_BATCH = 10_000;
	/** Manifest sidecar under each stream archive dir. */
	private static final String MANIFEST_FILE = "manifest.meta";
	/** Encoded {@link OpLogSegment} payload. */
	private static final String SEGMENT_FILE = "segment.bin";
	private static final String KEY_DOMAIN = "domain=";
	private static final String KEY_SHARD = "shard=";
	private static final String KEY_FROM_SEQ = "fromSeq=";
	private static final String KEY_TO_SEQ = "toSeq=";
	private static final String KEY_SCHEMA_EPOCH = "schemaEpoch=";
	private static final String KEY_CHECKSUM = "checksum=";
	private static final char MANIFEST_NL = '\n';

	private OpLogArchiveUtil() {
	}

	/**
	 * Archive manifest metadata written beside the segment.
	 *
	 * @param domain      domain type
	 * @param shard       shard id
	 * @param fromSeq     inclusive first archived seq
	 * @param toSeq       inclusive last archived seq
	 * @param schemaEpoch schema epoch from the last archived op (0 if empty)
	 * @param checksum    {@link OpLogCodec#segmentChecksum} of archived ops
	 */
	public record ArchiveManifest(
			String domain,
			int shard,
			long fromSeq,
			long toSeq,
			long schemaEpoch,
			long checksum
	) {
	}

	/**
	 * When {@code archiveRoot} is non-null, copy ops about to be truncated
	 * ({@code truncatedThrough+1 … toSeqInclusive}) then return; caller truncates.
	 * Fail-closed: I/O errors propagate (do not truncate afterward).
	 * <p>
	 * No-op when {@code archiveRoot} is null or {@code toSeqInclusive <= 0}.
	 */
	public static void archiveBeforeTruncate(
			OpLog opLog,
			Path archiveRoot,
			String domain,
			int shard,
			long toSeqInclusive
	) {
		if (archiveRoot == null || domain == null || toSeqInclusive <= 0L) {
			return;
		}
		final long fromSeq = Math.max(1L, opLog.truncatedThrough(domain, shard) + 1L);
		if (fromSeq > toSeqInclusive) {
			return;
		}
		archiveRange(opLog, archiveRoot, domain, shard, fromSeq, toSeqInclusive);
	}

	/**
	 * Copy/read ops in {@code [fromSeqInclusive, toSeqInclusive]} under {@code archiveRoot}.
	 * Layout: {@code archiveRoot/{domainHex}_{shard}/segment.bin} + {@code manifest.meta}.
	 *
	 * @return written manifest (empty range still writes a zero-op segment + manifest)
	 */
	public static ArchiveManifest archiveRange(
			OpLog opLog,
			Path archiveRoot,
			String domain,
			int shard,
			long fromSeqInclusive,
			long toSeqInclusive
	) {
		Objects.requireNonNull(opLog, "opLog");
		Objects.requireNonNull(archiveRoot, "archiveRoot");
		Objects.requireNonNull(domain, "domain");
		if (fromSeqInclusive > toSeqInclusive) {
			throw new IllegalArgumentException(
					"fromSeqInclusive=" + fromSeqInclusive + " > toSeqInclusive=" + toSeqInclusive);
		}
		final List<ReplicationOp> ops = readRange(opLog, domain, shard, fromSeqInclusive, toSeqInclusive);
		final long checksum = OpLogCodec.segmentChecksum(ops);
		final long schemaEpoch = ops.isEmpty() ? 0L : ops.getLast().schemaEpoch();
		final long fromSeq = ops.isEmpty() ? fromSeqInclusive : ops.getFirst().opSeq();
		final long toSeq = ops.isEmpty() ? toSeqInclusive : ops.getLast().opSeq();
		final OpLogSegment segment = new OpLogSegment(domain, shard, fromSeq, toSeq, ops, checksum);
		final Path streamDir = streamDir(archiveRoot, domain, shard);
		try {
			GridFs.createDirs(streamDir);
			GridFs.writeAtomic(streamDir.resolve(SEGMENT_FILE), OpLogCodec.encodeSegment(segment));
			final ArchiveManifest manifest = new ArchiveManifest(
					domain, shard, fromSeq, toSeq, schemaEpoch, checksum);
			writeManifest(streamDir.resolve(MANIFEST_FILE), manifest);
			return manifest;
		} catch (IOException e) {
			throw new IllegalStateException(
					"OpLog archive failed for " + domain + "#" + shard
							+ " [" + fromSeqInclusive + ".." + toSeqInclusive + "]",
					e);
		}
	}

	/**
	 * Load archived ops for {@code domain#shard} with {@code opSeq <= untilSeqInclusive}.
	 */
	public static List<ReplicationOp> readArchiveUntil(
			Path archiveRoot,
			String domain,
			int shard,
			long untilSeqInclusive
	) {
		Objects.requireNonNull(archiveRoot, "archiveRoot");
		Objects.requireNonNull(domain, "domain");
		final Path segmentPath = streamDir(archiveRoot, domain, shard).resolve(SEGMENT_FILE);
		if (!GridFs.isRegularFile(segmentPath)) {
			return List.of();
		}
		try {
			final OpLogSegment segment = OpLogCodec.decodeSegment(GridFs.readAll(segmentPath));
			if (segment.ops().isEmpty()) {
				return List.of();
			}
			final List<ReplicationOp> out = new ArrayList<>();
			for (ReplicationOp op : segment.ops()) {
				if (op.opSeq() <= untilSeqInclusive) {
					out.add(op);
				}
			}
			return List.copyOf(out);
		} catch (IOException e) {
			throw new IllegalStateException(
					"OpLog archive read failed for " + domain + "#" + shard, e);
		}
	}

	/** Read and parse manifest for a stream archive dir. */
	public static ArchiveManifest readManifest(Path archiveRoot, String domain, int shard) {
		Objects.requireNonNull(archiveRoot, "archiveRoot");
		Objects.requireNonNull(domain, "domain");
		final Path manifestPath = streamDir(archiveRoot, domain, shard).resolve(MANIFEST_FILE);
		try {
			return parseManifest(GridFs.readString(manifestPath));
		} catch (IOException e) {
			throw new IllegalStateException(
					"OpLog archive manifest read failed for " + domain + "#" + shard, e);
		}
	}

	@VisibleForTesting
	static Path streamDir(Path archiveRoot, String domain, int shard) {
		return archiveRoot.resolve(SealedShardPack.domainHex(domain) + "_" + shard);
	}

	@VisibleForTesting
	static void writeManifest(Path path, ArchiveManifest manifest) throws IOException {
		final StringBuilder body = new StringBuilder(128);
		body.append(KEY_DOMAIN).append(manifest.domain()).append(MANIFEST_NL);
		body.append(KEY_SHARD).append(manifest.shard()).append(MANIFEST_NL);
		body.append(KEY_FROM_SEQ).append(manifest.fromSeq()).append(MANIFEST_NL);
		body.append(KEY_TO_SEQ).append(manifest.toSeq()).append(MANIFEST_NL);
		body.append(KEY_SCHEMA_EPOCH).append(manifest.schemaEpoch()).append(MANIFEST_NL);
		body.append(KEY_CHECKSUM).append(manifest.checksum()).append(MANIFEST_NL);
		GridFs.writeAtomic(path, body.toString(), StandardCharsets.UTF_8);
	}

	@VisibleForTesting
	static ArchiveManifest parseManifest(String body) {
		String domain = null;
		int shard = -1;
		long fromSeq = -1L;
		long toSeq = -1L;
		long schemaEpoch = 0L;
		long checksum = 0L;
		final String[] lines = body.split("\\R");
		for (String line : lines) {
			if (line.startsWith(KEY_DOMAIN)) {
				domain = line.substring(KEY_DOMAIN.length());
			} else if (line.startsWith(KEY_SHARD)) {
				shard = Integer.parseInt(line.substring(KEY_SHARD.length()));
			} else if (line.startsWith(KEY_FROM_SEQ)) {
				fromSeq = Long.parseLong(line.substring(KEY_FROM_SEQ.length()));
			} else if (line.startsWith(KEY_TO_SEQ)) {
				toSeq = Long.parseLong(line.substring(KEY_TO_SEQ.length()));
			} else if (line.startsWith(KEY_SCHEMA_EPOCH)) {
				schemaEpoch = Long.parseLong(line.substring(KEY_SCHEMA_EPOCH.length()));
			} else if (line.startsWith(KEY_CHECKSUM)) {
				checksum = Long.parseLong(line.substring(KEY_CHECKSUM.length()));
			}
		}
		if (domain == null || shard < 0 || fromSeq < 0L || toSeq < 0L) {
			throw new IllegalStateException("incomplete archive manifest");
		}
		return new ArchiveManifest(domain, shard, fromSeq, toSeq, schemaEpoch, checksum);
	}

	private static List<ReplicationOp> readRange(
			OpLog opLog,
			String domain,
			int shard,
			long fromSeqInclusive,
			long toSeqInclusive
	) {
		final List<ReplicationOp> ops = new ArrayList<>();
		long from = fromSeqInclusive;
		while (from <= toSeqInclusive) {
			final List<ReplicationOp> batch = opLog.readFrom(domain, shard, from, READ_BATCH);
			if (batch.isEmpty()) {
				break;
			}
			for (ReplicationOp op : batch) {
				if (op.opSeq() > toSeqInclusive) {
					return List.copyOf(ops);
				}
				if (op.opSeq() >= fromSeqInclusive) {
					ops.add(op);
				}
				from = op.opSeq() + 1L;
			}
			if (batch.size() < READ_BATCH) {
				break;
			}
		}
		return List.copyOf(ops);
	}
}
