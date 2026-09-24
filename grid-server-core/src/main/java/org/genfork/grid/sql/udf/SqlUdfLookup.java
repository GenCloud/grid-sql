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
package org.genfork.grid.sql.udf;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.catalog.TableCatalog.FunctionDef;

/**
 * Thread-local catalog handle for residual UDF evaluation on the query filter path.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlUdfLookup {
	private static final ThreadLocal<TableCatalog> CATALOG = new ThreadLocal<>();

	private SqlUdfLookup() {
	}

	public static void bind(TableCatalog catalog) {
		CATALOG.set(catalog);
	}

	/**
	 * Push catalog binding; returns previous (nullable) for {@link #restore}.
	 */
	public static TableCatalog push(TableCatalog catalog) {
		final TableCatalog prev = CATALOG.get();
		CATALOG.set(catalog);
		return prev;
	}

	/** Restore previous catalog after nested execute (null clears). */
	public static void restore(TableCatalog previous) {
		if (previous == null) {
			CATALOG.remove();
		} else {
			CATALOG.set(previous);
		}
	}

	public static void clear() {
		CATALOG.remove();
	}

	public static FunctionDef require(String name) {
		final TableCatalog catalog = CATALOG.get();
		if (catalog == null) {
			throw new IllegalStateException("UDF catalog not bound for evaluation");
		}
		return catalog.requireFunction(name);
	}
}
