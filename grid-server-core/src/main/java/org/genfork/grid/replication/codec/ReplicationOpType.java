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

/**
 * Mutation op kinds in the durable OpLog stream.
 * <p>
 * SQL mapping (planner to wire):
 * <ul>
 *   <li>{@link #UPSERT} - full-row put ({@code INSERT}, replace, and structural {@code UPDATE} after local merge)</li>
 *   <li>{@link #DELETE} - row remove</li>
 *   <li>{@link #DDL} - catalog DDL text ({@code CREATE}/{@code DROP TABLE|INDEX}, {@code CREATE SCHEMA})
 *       shipped on the {@code _catalog} stream; value = UTF-8 SQL, {@code schemaEpoch} = catalog epoch</li>
 * </ul>
 * Structural {@code UPDATE SET col = col || / +} merges on the proposer into a final row blob,
 * then records {@link #UPSERT} (no separate MODIFY/APPEND op type).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public enum ReplicationOpType {
	UPSERT((byte) 1),
	DELETE((byte) 2),
	SNAPSHOT_MARKER((byte) 3),
	BARRIER((byte) 4),
	TX_BEGIN((byte) 5),
	TX_COMMIT((byte) 6),
	TX_ABORT((byte) 7),
	DDL((byte) 8);

	private final byte code;

	ReplicationOpType(byte code) {
		this.code = code;
	}

	public byte code() {
		return code;
	}

	public static ReplicationOpType fromCode(byte code) {
		for (ReplicationOpType type : values()) {
			if (type.code == code) {
				return type;
			}
		}
		throw new IllegalArgumentException("Unknown ReplicationOpType code: " + code);
	}
}