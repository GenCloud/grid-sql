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
package index.sql.udf;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.udf.SqlTableUdf;
import org.genfork.grid.store.TableStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Test TVF: returns committed row blobs from a named table/view store.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class ScanTableTvf implements SqlTableUdf {
	/** Bound by IT before CREATE FUNCTION / SELECT. */
	public static volatile TableCatalog CATALOG;

	@Override
	public List<byte[]> apply(Object[] args) {
		Objects.requireNonNull(args, "args");
		if (args.length < 1 || args[0] == null) {
			throw new IllegalArgumentException("ScanTableTvf requires table name arg");
		}
		final TableCatalog catalog = CATALOG;
		if (catalog == null) {
			throw new IllegalStateException("ScanTableTvf.CATALOG not bound");
		}
		final String table = String.valueOf(args[0]);
		final TableStore store = catalog.getStore(table);
		if (store == null) {
			throw new IllegalArgumentException("unknown table for TVF: " + table);
		}
		final List<byte[]> rows = new ArrayList<>();
		store.forEachCommitted((key, value) -> {
			if (value != null) {
				rows.add(value);
			}
		});
		return rows;
	}
}