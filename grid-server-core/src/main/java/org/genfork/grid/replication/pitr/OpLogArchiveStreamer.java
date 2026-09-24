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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.google.common.annotations.VisibleForTesting;

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;

/**
 * Append-only off-node OpLog archive shipper (PITR streaming path).
 * <p>
 * Layout: {@code streamRoot/{domainHex}_{shard}/append.bin} (length-prefixed
 * {@link OpLogCodec#encodeOp} frames) plus {@code cursor.meta} with last shipped seq.
 * Sync I/O on the calling thread — never Netty EL.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class OpLogArchiveStreamer {
	private static final int READ_BATCH = 10_000;
	private static final String APPEND_FILE = "append.bin";
	private static final String CURSOR_FILE = "cursor.meta";
	private static final String KEY_LAST_SEQ = "lastSeq=";
	private static final int LENGTH_PREFIX_BYTES = 4;
	private static final String STREAM_SUBDIR = "stream";

	private OpLogArchiveStreamer() {
	}

	/**
	 * Default off-node stream root when config {@code streamDir} is blank.
	 */
	public static Path defaultStreamRoot(Path archiveRoot) {
		Objects.requireNonNull(archiveRoot, "archiveRoot");
		return archiveRoot.resolve(STREAM_SUBDIR);
	}

	/**
	 * Ship ops from last cursor (exclusive) through {@code opLog.lastSeq} for the stream.
	 *
	 * @return number of ops appended (0 when already caught up)
	 */
	public static int shipCatchUp(OpLog opLog, Path streamRoot, String domain, int shard) {
		Objects.requireNonNull(opLog, "opLog");
		Objects.requireNonNull(streamRoot, "streamRoot");
		Objects.requireNonNull(domain, "domain");
		final long lastSeq = opLog.lastSeq(domain, shard);
		if (lastSeq <= 0L) {
			return 0;
		}
		final long fromSeq = lastShippedSeq(streamRoot, domain, shard) + 1L;
		if (fromSeq > lastSeq) {
			return 0;
		}
		return shipRange(opLog, streamRoot, domain, shard, fromSeq, lastSeq);
	}

	/**
	 * Append ops in {@code [fromSeqInclusive, toSeqInclusive]} to the off-node stream.
	 *
	 * @return number of ops appended
	 */
	public static int shipRange(
			OpLog opLog,
			Path streamRoot,
			String domain,
			int shard,
			long fromSeqInclusive,
			long toSeqInclusive
	) {
		Objects.requireNonNull(opLog, "opLog");
		Objects.requireNonNull(streamRoot, "streamRoot");
		Objects.requireNonNull(domain, "domain");
		if (fromSeqInclusive > toSeqInclusive) {
			throw new IllegalArgumentException(
					"fromSeqInclusive=" + fromSeqInclusive + " > toSeqInclusive=" + toSeqInclusive);
		}
		final List<ReplicationOp> ops = readRange(opLog, domain, shard, fromSeqInclusive, toSeqInclusive);
		if (ops.isEmpty()) {
			return 0;
		}
		appendOps(streamRoot, domain, shard, ops);
		writeCursor(streamRoot, domain, shard, ops.getLast().opSeq());
		return ops.size();
	}

	/**
	 * Read appended stream ops with {@code opSeq <= untilSeqInclusive}.
	 */
	public static List<ReplicationOp> readStreamUntil(
			Path streamRoot,
			String domain,
			int shard,
			long untilSeqInclusive
	) {
		Objects.requireNonNull(streamRoot, "streamRoot");
		Objects.requireNonNull(domain, "domain");
		final Path appendPath = streamDir(streamRoot, domain, shard).resolve(APPEND_FILE);
		if (!GridFs.isRegularFile(appendPath)) {
			return List.of();
		}
		try {
			final byte[] raw = GridFs.readAll(appendPath);
			final List<ReplicationOp> out = new ArrayList<>();
			int offset = 0;
			while (offset + LENGTH_PREFIX_BYTES <= raw.length) {
				final int frameLen = ByteBuffer.wrap(raw, offset, LENGTH_PREFIX_BYTES)
						.order(ByteOrder.LITTLE_ENDIAN)
						.getInt();
				offset += LENGTH_PREFIX_BYTES;
				if (frameLen < 0 || offset + frameLen > raw.length) {
					throw new IllegalStateException("corrupt OpLog archive stream frame at " + appendPath);
				}
				final byte[] frame = new byte[frameLen];
				System.arraycopy(raw, offset, frame, 0, frameLen);
				offset += frameLen;
				final ReplicationOp op = OpLogCodec.decodeOp(frame);
				if (op.opSeq() <= untilSeqInclusive) {
					out.add(op);
				}
			}
			return List.copyOf(out);
		} catch (IOException e) {
			throw new IllegalStateException(
					"OpLog archive stream read failed for " + domain + "#" + shard, e);
		}
	}

	/**
	 * Last successfully shipped seq for the stream (0 when no cursor).
	 */
	public static long lastShippedSeq(Path streamRoot, String domain, int shard) {
		Objects.requireNonNull(streamRoot, "streamRoot");
		Objects.requireNonNull(domain, "domain");
		final Path cursorPath = streamDir(streamRoot, domain, shard).resolve(CURSOR_FILE);
		if (!GridFs.isRegularFile(cursorPath)) {
			return 0L;
		}
		try {
			final String body = GridFs.readString(cursorPath);
			for (String line : body.split("\\R")) {
				if (line.startsWith(KEY_LAST_SEQ)) {
					return Long.parseLong(line.substring(KEY_LAST_SEQ.length()));
				}
			}
			return 0L;
		} catch (IOException e) {
			throw new IllegalStateException(
					"OpLog archive stream cursor read failed for " + domain + "#" + shard, e);
		}
	}

	@VisibleForTesting
	static Path streamDir(Path streamRoot, String domain, int shard) {
		return streamRoot.resolve(SealedShardPack.domainHex(domain) + "_" + shard);
	}

	@VisibleForTesting
	static void writeCursor(Path streamRoot, String domain, int shard, long lastSeq) {
		final Path dir = streamDir(streamRoot, domain, shard);
		try {
			GridFs.createDirs(dir);
			GridFs.writeAtomic(dir.resolve(CURSOR_FILE), KEY_LAST_SEQ + lastSeq + '\n', StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException(
					"OpLog archive stream cursor write failed for " + domain + "#" + shard, e);
		}
	}

	private static void appendOps(Path streamRoot, String domain, int shard, List<ReplicationOp> ops) {
		final Path dir = streamDir(streamRoot, domain, shard);
		final Path appendPath = dir.resolve(APPEND_FILE);
		try {
			GridFs.createDirs(dir);
			for (ReplicationOp op : ops) {
				final byte[] encoded = OpLogCodec.encodeOp(op);
				final byte[] frame = new byte[LENGTH_PREFIX_BYTES + encoded.length];
				ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).putInt(encoded.length);
				System.arraycopy(encoded, 0, frame, LENGTH_PREFIX_BYTES, encoded.length);
				Files.write(
						appendPath,
						frame,
						StandardOpenOption.CREATE,
						StandardOpenOption.WRITE,
						StandardOpenOption.APPEND);
			}
		} catch (IOException e) {
			throw new IllegalStateException(
					"OpLog archive stream append failed for " + domain + "#" + shard, e);
		}
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
