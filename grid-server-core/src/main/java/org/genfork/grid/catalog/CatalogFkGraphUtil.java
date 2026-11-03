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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

	/**
	 * Parents-before-children catalog keys for compact {@code ddl.sql} / recover.
	 * Alphabetical emission alone places FK children before parents (heal/recover fail-closed).
	 * Cycles / missing parents: emit remaining keys lexicographically.
	 */
	public static List<String> parentsFirstTableKeys(Iterable<TableSchema> schemas) {
		final ArrayList<TableSchema> list = new ArrayList<>();
		for (TableSchema schema : schemas) {
			if (schema != null && schema.catalogKey() != null && !schema.catalogKey().isBlank()) {
				list.add(schema);
			}
		}
		list.sort(Comparator.comparing(s -> normalizeCatalogKey(s.catalogKey())));
		final HashMap<String, List<String>> childToParents = new HashMap<>();
		final HashMap<String, String> normToOriginal = new HashMap<>();
		final HashSet<String> keys = new HashSet<>();
		for (TableSchema schema : list) {
			final String original = schema.catalogKey();
			final String key = normalizeCatalogKey(original);
			keys.add(key);
			normToOriginal.putIfAbsent(key, original);
			addFkEdges(childToParents, schema);
		}
		final ArrayList<String> ordered = new ArrayList<>(list.size());
		final HashSet<String> placed = new HashSet<>();
		while (placed.size() < keys.size()) {
			String nextNorm = null;
			for (TableSchema schema : list) {
				final String key = normalizeCatalogKey(schema.catalogKey());
				if (placed.contains(key)) {
					continue;
				}
				if (parentsPlacedOrExternal(childToParents.getOrDefault(key, List.of()), placed, keys)) {
					nextNorm = key;
					break;
				}
			}
			if (nextNorm == null) {
				for (TableSchema schema : list) {
					final String key = normalizeCatalogKey(schema.catalogKey());
					if (!placed.contains(key)) {
						nextNorm = key;
						break;
					}
				}
			}
			if (nextNorm == null) {
				break;
			}
			placed.add(nextNorm);
			ordered.add(normToOriginal.get(nextNorm));
		}
		return List.copyOf(ordered);
	}

	private static boolean parentsPlacedOrExternal(
			List<String> parents,
			Set<String> placed,
			Set<String> localKeys
	) {
		for (String parent : parents) {
			if (localKeys.contains(parent) && !placed.contains(parent)) {
				return false;
			}
		}
		return true;
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
