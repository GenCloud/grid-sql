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
package org.genfork.grid.query.plan;

/**
 * LIMIT / OFFSET for SELECT. Unbounded SELECT uses {@link #UNBOUNDED_LIMIT} so
 * {@code offset + limit} stays within {@code int} (unlike {@link Integer#MAX_VALUE}).
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public record PagingData(int offset, int limit) {
	/**
	 * Sentinel LIMIT when the statement has no LIMIT clause (unbounded result).
	 */
	public static final int UNBOUNDED_LIMIT = 1_000_000_000;

	/**
	 * {@code true} when LIMIT is a real bound suitable for index early-stop push-down.
	 */
	public boolean hasBoundedLimit() {
		return limit > 0 && limit < UNBOUNDED_LIMIT;
	}
}
