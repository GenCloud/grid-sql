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
package org.genfork.grid.replication.codec;

import java.util.Arrays;
import java.util.Objects;

/**
 * Atomic replication unit. {@code opSeq} is the global ORCHID commit sequence;
 * per-(domain, shard) apply/OpLog streams may see gaps when other shards advance the counter.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public record ReplicationOp(
		String domainType,
		int shard,
		long opSeq,
		ReplicationOpType type,
		byte[] key,
		byte[] value,
		long schemaEpoch,
		long checksum
) {
	public ReplicationOp {
		Objects.requireNonNull(domainType, "domainType");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(key, "key");
		key = Arrays.copyOf(key, key.length);
		value = value == null ? null : Arrays.copyOf(value, value.length);
	}

	@Override
	public byte[] key() {
		return Arrays.copyOf(key, key.length);
	}

	@Override
	public byte[] value() {
		return value == null ? null : Arrays.copyOf(value, value.length);
	}
}
