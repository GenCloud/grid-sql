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
import org.genfork.grid.sql.ast.SelectAst.FunctionSelectItem;
import org.genfork.grid.sql.ast.SelectAst.SelectItem;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;

import java.util.List;
import java.util.Objects;

/**
 * Helpers for mutating scalar UDF detection on SELECT plans.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlUdfMutatingOps {
	private SqlUdfMutatingOps() {
	}

	/**
	 * True when the select list calls at least one catalog UDF marked {@code mutating}.
	 */
	public static boolean selectHasMutatingUdf(SelectSql select, TableCatalog catalog) {
		Objects.requireNonNull(select, "select");
		Objects.requireNonNull(catalog, "catalog");
		final List<SelectItem> items = select.selectItems();
		if (items == null || items.isEmpty()) {
			return false;
		}
		for (SelectItem item : items) {
			if (item instanceof FunctionSelectItem fn) {
				final FunctionDef def = catalog.getFunction(fn.functionName());
				if (def != null && def.mutating()) {
					return true;
				}
			}
		}
		return false;
	}
}