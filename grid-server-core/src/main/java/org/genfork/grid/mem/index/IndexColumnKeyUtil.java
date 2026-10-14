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
package org.genfork.grid.mem.index;

import java.util.List;
import java.util.Objects;

/**
 * Normalized immutable list keys for composite secondary-index maps.
 * <p>
 * {@link GridCompositeIndex} stores trees under {@code List<String>} keys; DROP must
 * remove with the same immutable list shape as CREATE wiring ({@link List#copyOf}).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public final class IndexColumnKeyUtil {
	private IndexColumnKeyUtil() {
	}

	/**
	 * Immutable composite map key (column order preserved).
	 */
	public static List<String> compositeKey(List<String> columns) {
		Objects.requireNonNull(columns, "columns");
		if (columns.size() < 2) {
			throw new IllegalArgumentException("composite key requires at least two columns");
		}
		return List.copyOf(columns);
	}
}
