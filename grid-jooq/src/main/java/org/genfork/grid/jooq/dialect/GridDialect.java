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
package org.genfork.grid.jooq.dialect;

/**
 * Logical SimplifiedSql dialect capabilities owned by {@code grid-jooq}.
 * <p>
 * OSS jOOQ has no {@code SQLDialect.GRID} enum; the host render dialect stays
 * {@link org.jooq.SQLDialect#DEFAULT}. Critical clauses (ON CONFLICT, RETURNING,
 * TRUNCATE, FOR UPDATE SKIP LOCKED) are rendered by Grid QueryParts / {@link GridDSL},
 * not by SQLITE / VisitListener rewrites.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public final class GridDialect {
	/** Singleton description of the product logical dialect. */
	public static final GridDialect INSTANCE = new GridDialect();

	private static final String NAME = "SimplifiedSql";

	private GridDialect() {
	}

	/** Human-readable logical dialect name (not a jOOQ {@code SQLDialect} enum constant). */
	public String name() {
		return NAME;
	}

	/** {@code INSERT … ON CONFLICT (cols) DO UPDATE SET …} including {@code EXCLUDED.col}. */
	public boolean supportsOnConflictDoUpdate() {
		return true;
	}

	/** {@code INSERT … ON CONFLICT (cols) DO NOTHING}. */
	public boolean supportsOnConflictDoNothing() {
		return true;
	}

	/** Native {@code INSERT … RETURNING …} (no {@code last_insert_rowid} emulation). */
	public boolean supportsReturning() {
		return true;
	}

	/** {@code TRUNCATE TABLE} (not bare {@code DELETE FROM} without WHERE). */
	public boolean supportsTruncate() {
		return true;
	}

	/** {@code FOR UPDATE SKIP LOCKED}. */
	public boolean supportsForUpdateSkipLocked() {
		return true;
	}

	/** Indexed bind markers {@code ?}. */
	public boolean supportsIndexedBinds() {
		return true;
	}
}
