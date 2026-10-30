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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Foreign-key dependency graph helpers and safe analyze-stats file names for {@link TableCatalog}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class CatalogFkGraphUtil {

	private CatalogFkGraphUtil() {
	}

	public static void addFkEdges(Map<String, List<String>> edges, TableSchema schema) {
		final String from = normalizeCatalogKey(schema.catalogKey());
		for (FkDef fk : schema.foreignKeys()) {
			final String parent = normalizeCatalogKey(fk.parentCatalogKey());
			if (parent.equals(from)) {
				continue;
			}
			edges.computeIfAbsent(from, _ -> new ArrayList<>()).add(parent);
		}
	}

	public static boolean hasCycleFrom(
			Map<String, List<String>> edges,
			String node,
			LinkedHashSet<String> stack
	) {
		if (!stack.add(node)) {
			return true;
		}
		final List<String> next = edges.getOrDefault(node, List.of());
		for (String child : next) {
			if (hasCycleFrom(edges, child, stack)) {
				return true;
			}
		}
		stack.remove(node);
		return false;
	}

	/** File-system safe fragment for analyze-stats sidecars ({@code tableKey} → underscore form). */
	public static String safeFileName(String tableKey) {
		return tableKey.replace('.', '_').replace('/', '_');
	}

	private static String normalizeCatalogKey(String catalogKey) {
		return catalogKey.toLowerCase(Locale.ROOT);
	}
}
