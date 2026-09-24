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
package org.genfork.grid.catalog;

/**
 * Fixed logical-row wire sizes for UUID / binary temporal types.
 *
 * Lives in {@code grid-commons} (shared client/server; JDK-only).
 *
 * @author: GenCloud
 * @date: 2025/07
 * @since: 1.0
 */
public final class SqlTypeWireSizes {
	/** {@link SqlType#UUID} — 16 raw bytes (MSB + LSB big-endian). */
	public static final int UUID_BYTES = 16;
	/** {@link SqlType#DATE} — epoch day as {@code int}. */
	public static final int DATE_BYTES = Integer.BYTES;
	/** {@link SqlType#TIME} — nano-of-day as {@code long}. */
	public static final int TIME_BYTES = Long.BYTES;
	/** {@link SqlType#TIMESTAMPTZ} — epoch second ({@code long}) + nano ({@code int}), UTC. */
	public static final int TIMESTAMPTZ_BYTES = Long.BYTES + Integer.BYTES;

	private SqlTypeWireSizes() {
	}
}