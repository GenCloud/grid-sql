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
package org.genfork.grid.replication.log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.durable.LengthPrefixedAppendLog;
import org.genfork.grid.replication.offheap.OffHeapBuffer;

/**
 * MMF-backed append-only op store. Records: int32 length + payload. Meta watermark in sibling file.
 * <p>
 * Replay recovers a torn tail (crash / kill mid-append) by truncating to the last intact record
 * so the node can boot without purging durable data. Graceful SIGTERM flushes via
 * {@link LengthPrefixedAppendLog#close()}; hard kills (SIGKILL / SIGSEGV) rely on this replay path.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class FileDurableOpStore implements AutoCloseable {
	private static final Logger log = LoggerFactory.getLogger(FileDurableOpStore.class);

	/** Max single record payload (bytes). */
	private static final int MAX_RECORD_PAYLOAD_BYTES = 64 * 1024 * 1024;
	private static final int LENGTH_HEADER_BYTES = LengthPrefixedAppendLog.LENGTH_HEADER_BYTES;

	private final Path metaPath;
	private final LengthPrefixedAppendLog logFile;
	private volatile long truncatedThrough;

	public FileDurableOpStore(Path dataDir, String streamId, boolean fsync) throws IOException {
		GridFs.createDirs(dataDir);
		this.metaPath = dataDir.resolve(streamId + ".meta");
		this.truncatedThrough = readTruncatedThrough();
		this.logFile = new LengthPrefixedAppendLog(dataDir.resolve(streamId + ".log"), fsync);
	}

	public long truncatedThrough() {
		return truncatedThrough;
	}

	/** @return file offset of the record start (length header) */
	public long append(ReplicationOp op) throws IOException {
		try (OffHeapBuffer encoded = OpLogCodec.encodeOpOffHeap(op)) {
			return logFile.appendOffHeap(encoded);
		}
	}

	/**
	 * Append without fsync; caller must {@link #force()} before durable visibility.
	 * @return file offset of the record start (length header)
	 */
	public long appendDeferred(ReplicationOp op) throws IOException {
		try (OffHeapBuffer encoded = OpLogCodec.encodeOpOffHeap(op)) {
			return logFile.appendOffHeapDeferred(encoded);
		}
	}

	/** Group fsync for deferred appends on this stream. */
	public void force() throws IOException {
		logFile.sync();
	}

	public void appendEncoded(byte[] payload) throws IOException {
		logFile.appendBytes(payload);
	}

	/**
	 * Batch append with a single group fsync at the end (when store fsync=true).
	 * @return file offsets aligned with {@code ops}
	 */
	public long[] appendBatch(List<ReplicationOp> ops) throws IOException {
		return logFile.appendBatchEncoded(ops, OpLogCodec::encodeOpOffHeap);
	}

	public void setTruncatedThrough(long seq) throws IOException {
		this.truncatedThrough = Math.max(this.truncatedThrough, seq);
		GridFs.writeAtomic(metaPath, Long.toString(this.truncatedThrough));
	}

	public List<ReplicationOp> replayOps() throws IOException {
		final List<ReplicationOp> ops = new ArrayList<>();
		for (Record rec : replayWithOffsets()) {
			ops.add(rec.op());
		}
		return ops;
	}

	public List<Record> replayWithOffsets() throws IOException {
		final List<Record> records = new ArrayList<>();
		long pos = 0L;
		final long end = logFile.size();
		while (pos + LENGTH_HEADER_BYTES <= end) {
			final OffHeapBuffer lenView = logFile.mapped().view(pos, LENGTH_HEADER_BYTES);
			final int len = lenView.getInt();
			if (len <= 0 || len > MAX_RECORD_PAYLOAD_BYTES) {
				truncateTornTail(pos, "invalid length=" + len);
				break;
			}
			if (pos + LENGTH_HEADER_BYTES + len > end) {
				truncateTornTail(pos, "truncated record need=" + (LENGTH_HEADER_BYTES + len)
						+ " remain=" + (end - pos));
				break;
			}
			final long recordOffset = pos;
			final OffHeapBuffer payload = logFile.mapped().view(pos + LENGTH_HEADER_BYTES, len);
			final ReplicationOp op = OpLogCodec.decodeOp(payload);
			if (op.opSeq() > truncatedThrough) {
				records.add(new Record(op, recordOffset));
			}
			pos += (long) LENGTH_HEADER_BYTES + len;
		}
		return records;
	}

	/**
	 * Crash / kill may leave a zeroed or partial length header at EOF.
	 * Truncate logical size to last intact byte so boot can continue without data purge.
	 */
	private void truncateTornTail(long goodEnd, String reason) throws IOException {
		final long end = logFile.size();
		if (goodEnd >= end) {
			return;
		}
		log.warn("OpLog torn-tail recovery truncate {} -> {} ({})", end, goodEnd, reason);
		logFile.truncateLogical(goodEnd);
	}

	public record Record(ReplicationOp op, long offset) {
	}

	public ReplicationOp readAt(long recordOffset) {
		final OffHeapBuffer lenView = logFile.mapped().view(recordOffset, LENGTH_HEADER_BYTES);
		final int len = lenView.getInt();
		final OffHeapBuffer payload = logFile.mapped().view(recordOffset + LENGTH_HEADER_BYTES, len);
		return OpLogCodec.decodeOp(payload);
	}

	private long readTruncatedThrough() throws IOException {
		if (!GridFs.exists(metaPath)) {
			return 0L;
		}
		final String raw = GridFs.readString(metaPath).trim();
		if (raw.isEmpty()) {
			return 0L;
		}
		return Long.parseLong(raw);
	}

	@Override
	public void close() throws IOException {
		logFile.close();
	}
}
