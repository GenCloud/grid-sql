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

import java.util.List;

import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.log.OpLog;

/**
 * Scan {@code _catalog} OpLog for the max DDL {@code schemaEpoch} (proposer floor seed).
 * <p>
 * Raises {@code ensureEpochAtLeast} only — never {@code noteApplied} (would silent-skip tip DDL).
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class CatalogOpLogEpochUtil {
	private static final int SCAN_BATCH = 256;
	private static final long FIRST_SEQ = 1L;

	private CatalogOpLogEpochUtil() {
	}

	/**
	 * @return highest {@link ReplicationOpType#DDL} schemaEpoch in the stream, or {@code 0}
	 */
	public static long maxDdlSchemaEpoch(OpLog opLog, String domainType, int shard) {
		if (opLog == null || domainType == null || domainType.isBlank()) {
			return 0L;
		}
		long maxEpoch = 0L;
		long fromSeq = FIRST_SEQ;
		while (true) {
			final List<ReplicationOp> batch = opLog.readFrom(domainType, shard, fromSeq, SCAN_BATCH);
			if (batch.isEmpty()) {
				break;
			}
			for (int i = 0, n = batch.size(); i < n; i++) {
				final ReplicationOp op = batch.get(i);
				if (op != null && op.type() == ReplicationOpType.DDL && op.schemaEpoch() > maxEpoch) {
					maxEpoch = op.schemaEpoch();
				}
				if (op != null) {
					fromSeq = op.opSeq() + 1L;
				}
			}
			if (batch.size() < SCAN_BATCH) {
				break;
			}
		}
		return maxEpoch;
	}
}
