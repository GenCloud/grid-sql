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
package org.genfork.grid.codec.duplex;

import org.genfork.grid.replication.metrics.ReplicationMetrics;

/**
 * Verifies dataLane ↔ parityLane complementarity and repairs per {@link DuplexRepairMode}.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public class DuplexVerifier {
	private final DuplexRepairMode repairMode;
	private long mismatchTotal;
	private long repairTotal;
	private long uncorrectableTotal;

	public DuplexVerifier(DuplexRepairMode repairMode) {
		this.repairMode = repairMode == null ? DuplexRepairMode.FAIL : repairMode;
	}

	public DuplexBlob verifyOrRepair(DuplexBlob blob) {
		if (ParityLane.matches(blob.dataLane(), blob.parityLane())) {
			return blob;
		}
		mismatchTotal++;
		ReplicationMetrics.recordDuplexMismatch();
		return switch (repairMode) {
			case FAIL -> {
				uncorrectableTotal++;
				ReplicationMetrics.recordDuplexUncorrectable();
				throw new IllegalStateException("Duplex lane mismatch and repairMode=FAIL");
			}
			case REBUILD_DATA_FROM_PARITY -> {
				repairTotal++;
				ReplicationMetrics.recordDuplexRepair();
				final byte[] data = ParityLane.toDataLane(blob.parityLane());
				final byte[] parity = blob.parityLane();
				final long checksum = DuplexBlob.checksum(data, parity, blob.logicalLen(), blob.schemaEpoch());
				yield new DuplexBlob(data, parity, blob.logicalLen(), blob.schemaEpoch(), checksum);
			}
			case REBUILD_PARITY_FROM_DATA -> {
				repairTotal++;
				ReplicationMetrics.recordDuplexRepair();
				final byte[] data = blob.dataLane();
				final byte[] parity = ParityLane.fromDataLane(data);
				final long checksum = DuplexBlob.checksum(data, parity, blob.logicalLen(), blob.schemaEpoch());
				yield new DuplexBlob(data, parity, blob.logicalLen(), blob.schemaEpoch(), checksum);
			}
		};
	}

	public long mismatchTotal() {
		return mismatchTotal;
	}

	public long repairTotal() {
		return repairTotal;
	}

	public long uncorrectableTotal() {
		return uncorrectableTotal;
	}
}
