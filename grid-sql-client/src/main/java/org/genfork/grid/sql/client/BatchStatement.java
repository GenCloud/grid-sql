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
package org.genfork.grid.sql.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.genfork.grid.sql.netty.SqlWire;

/**
 * One indexed-SQL slot in a bound {@code BATCH_EXEC} (multi-result SELECT / mixed batch).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public record BatchStatement(String sql, Object[] binds) {
	private static final Object[] EMPTY_BINDS = new Object[0];

	public BatchStatement {
		Objects.requireNonNull(sql, "sql");
		if (binds == null || binds.length == 0) {
			binds = EMPTY_BINDS;
		} else {
			binds = binds.clone();
		}
	}

	public BatchStatement(String sql) {
		this(sql, EMPTY_BINDS);
	}

	/**
	 * Copy SPI slots to wire {@code BATCH_EXEC} layout.
	 */
	public static List<SqlWire.BatchStatement> toWire(List<BatchStatement> statements) {
		if (statements == null || statements.isEmpty()) {
			return List.of();
		}
		final List<SqlWire.BatchStatement> wire = new ArrayList<>(statements.size());
		for (BatchStatement statement : statements) {
			if (statement == null) {
				continue;
			}
			wire.add(new SqlWire.BatchStatement(statement.sql(), statement.binds()));
		}
		return List.copyOf(wire);
	}
}
