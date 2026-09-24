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

/**
 * Named in-TX savepoint handle (in-memory until COMMIT; no OpLog).
 * <p>
 * Create via {@link TxContext#savepoint(String)}; use {@link TxContext#rollbackTo(Savepoint)}
 * / {@link TxContext#release(Savepoint)}. Not a JDBC {@code java.sql.Savepoint}.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public interface Savepoint {
	/** SQL identifier used with SAVEPOINT / ROLLBACK TO / RELEASE. */
	String name();

	/** Construct a handle from a known SQL name (e.g. JDBC bridge). */
	static Savepoint of(String name) {
		return new NamedSavepoint(SqlSavepointSql.requireIdent(name));
	}
}