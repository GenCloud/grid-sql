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
package org.genfork.grid.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable copies that allow {@code null} values (unlike {@link Map#copyOf}/{@link List#copyOf}).
 * <p>
 * DML SET / VALUES / ON CONFLICT maps routinely carry SQL NULL; {@code Map.copyOf} NPEs on nulls.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public final class SqlNullSafeCollections {
	private SqlNullSafeCollections() {
	}

	/**
	 * Unmodifiable LinkedHashMap copy; {@code null} values preserved. Empty → {@link Map#of()}.
	 */
	public static Map<String, Object> freezeMap(Map<String, Object> map) {
		if (map == null) {
			return null;
		}
		if (map.isEmpty()) {
			return Map.of();
		}
		return Collections.unmodifiableMap(new LinkedHashMap<>(map));
	}

	/**
	 * Unmodifiable ArrayList copy; {@code null} elements preserved. Empty → {@link List#of()}.
	 */
	public static List<Object> freezeList(List<Object> list) {
		if (list == null) {
			return null;
		}
		if (list.isEmpty()) {
			return List.of();
		}
		return Collections.unmodifiableList(new ArrayList<>(list));
	}
}
