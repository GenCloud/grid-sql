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

import org.genfork.grid.sql.SqlBinds;

import java.util.TreeMap;

/**
 * Shared positional bind bookkeeping for embed/remote statements.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
abstract class AbstractBoundStatement implements Statement {
	protected final String sql;
	private final TreeMap<Integer, Object> binds = new TreeMap<>();
	private int fetchWindowOverride;

	protected AbstractBoundStatement(String sql) {
		this.sql = sql;
		this.fetchWindowOverride = 0;
	}

	@Override
	public Statement bind(int index, Object value) {
		if (index < 0) {
			throw new IllegalArgumentException("bind index must be >= 0");
		}

		binds.put(index, value);
		return this;
	}

	@Override
	public Statement bind(String name, Object value) {
		throw new UnsupportedOperationException("named bind not supported; use positional ?");
	}

	@Override
	public Statement fetchWindow(int rows) {
		if (rows < 1) {
			throw new IllegalArgumentException("fetchWindow must be >= 1");
		}
		this.fetchWindowOverride = rows;
		return this;
	}

	protected Object[] boundArgs() {
		return SqlBinds.dense(binds);
	}

	/** 0 = use connection default. */
	protected int fetchWindowOrZero() {
		return fetchWindowOverride;
	}
}