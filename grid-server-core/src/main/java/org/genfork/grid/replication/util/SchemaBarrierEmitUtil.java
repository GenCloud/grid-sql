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

import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;

import java.util.List;

/**
 * Helpers for deferred schema {@link ReplicationOpType#BARRIER} emit / pending clear.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class SchemaBarrierEmitUtil {
	private static final int TAIL_READ_LIMIT = 1;

	private SchemaBarrierEmitUtil() {
	}

	/**
	 * True when the OpLog stream tip is already a BARRIER for {@code schemaEpoch}.
	 */
	public static boolean isCurrentEpochBarrierAtTip(OpLog opLog, String domainType, int shard, long schemaEpoch) {
		if (opLog == null || domainType == null) {
			return false;
		}
		final long last = opLog.lastSeq(domainType, shard);
		if (last <= 0L) {
			return false;
		}
		final List<ReplicationOp> tail = opLog.readFrom(domainType, shard, last, TAIL_READ_LIMIT);
		if (tail.isEmpty()) {
			return false;
		}
		return isCurrentEpochBarrier(tail.getFirst(), schemaEpoch);
	}

	/**
	 * True when {@code op} is a BARRIER for {@code schemaEpoch}.
	 */
	public static boolean isCurrentEpochBarrier(ReplicationOp op, long schemaEpoch) {
		return op != null
				&& op.type() == ReplicationOpType.BARRIER
				&& op.schemaEpoch() == schemaEpoch;
	}

	/**
	 * True when the domain has at least one OpLog stream with {@code lastSeq > 0}.
	 */
	public static boolean hasCommittedDomainStreams(OpLog opLog, String domainType) {
		if (opLog == null || domainType == null) {
			return false;
		}
		for (String streamKey : opLog.streamKeys()) {
			if (!OpLogStreamKeyUtil.startsWithDomain(streamKey, domainType)) {
				continue;
			}
			final OpLogStreamKeyUtil.StreamKey parsed = OpLogStreamKeyUtil.parse(streamKey);
			if (parsed == null) {
				continue;
			}
			if (opLog.lastSeq(domainType, parsed.shard()) > 0L) {
				return true;
			}
		}
		return false;
	}
}