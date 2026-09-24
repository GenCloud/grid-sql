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

/**
 * Process-wide duplex codec toggle and holder (wired from {@code grid.codec.duplex}).
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public final class DuplexCodecSupport {
	private static volatile boolean enabled = false;
	private static volatile boolean applyToMapValues = true;
	private static volatile boolean applyToReplicationLog = true;
	private static volatile QuartetDuplexCodec codec = new QuartetDuplexCodec(DuplexRepairMode.REBUILD_DATA_FROM_PARITY);

	private DuplexCodecSupport() {
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void setEnabled(boolean enabled) {
		DuplexCodecSupport.enabled = enabled;
	}

	public static boolean isApplyToMapValues() {
		return applyToMapValues;
	}

	public static void setApplyToMapValues(boolean applyToMapValues) {
		DuplexCodecSupport.applyToMapValues = applyToMapValues;
	}

	public static boolean isApplyToReplicationLog() {
		return applyToReplicationLog;
	}

	public static void setApplyToReplicationLog(boolean applyToReplicationLog) {
		DuplexCodecSupport.applyToReplicationLog = applyToReplicationLog;
	}

	public static QuartetDuplexCodec getCodec() {
		return codec;
	}

	public static void configure(boolean enabledFlag,
	                             DuplexRepairMode repairMode,
	                             boolean verifyOnWrite,
	                             boolean verifyOnRead,
	                             long schemaEpoch,
	                             boolean mapValues,
	                             boolean replicationLog) {
		enabled = enabledFlag;
		applyToMapValues = mapValues;
		applyToReplicationLog = replicationLog;
		final QuartetDuplexCodec next = new QuartetDuplexCodec(repairMode == null
				? DuplexRepairMode.REBUILD_DATA_FROM_PARITY
				: repairMode);
		next.setVerifyOnWrite(verifyOnWrite);
		next.setVerifyOnRead(verifyOnRead);
		next.setSchemaEpoch(schemaEpoch);
		codec = next;
	}

	public static boolean isActiveForMap() {
		return enabled && applyToMapValues;
	}

	public static boolean isActiveForReplication() {
		return enabled && applyToReplicationLog;
	}
}
