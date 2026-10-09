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
package org.genfork.grid.sql.exec;

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.catalog.TableCatalog.ViewDef;
import org.genfork.grid.catalog.TableSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * DDL-edge catalog column lookup for VIEW / MV schema inference (FROM / JOIN sides).
 * <p>
 * Resolves {@link TableSchema} columns (base table or MV backing table) or plain
 * {@link ViewDef#columns()}; never requires a {@code TableStore}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
public final class SqlCatalogSourceColumnsUtil {
	private static final String ERR_UNKNOWN_TABLE = "Unknown table: ";

	private SqlCatalogSourceColumnsUtil() {
	}

	/**
	 * Columns available from a catalog key for DDL projection inference.
	 *
	 * @return new mutable list (never the live catalog collection)
	 */
	public static List<ColumnDef> columnsForCatalogKey(TableCatalog catalog, String catalogKey) {
		Objects.requireNonNull(catalog, "catalog");
		Objects.requireNonNull(catalogKey, "catalogKey");
		final TableSchema schema = catalog.getSchema(catalogKey);
		if (schema != null) {
			return new ArrayList<>(schema.columns());
		}
		final ViewDef view = catalog.getView(catalogKey);
		if (view != null && !view.materialized() && !view.columns().isEmpty()) {
			return new ArrayList<>(view.columns());
		}
		throw new IllegalArgumentException(ERR_UNKNOWN_TABLE + catalogKey);
	}
}
