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

import java.util.Objects;

/**
 * SQL CHECK constraint retained as an ANTLR-validated expression.
 *
 * @author: GenCloud
 * @date: 2025/07
 * @since: 1.0
 */
public record CheckDef(String name, String expressionSql) {
	public CheckDef {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(expressionSql, "expressionSql");
		if (name.isBlank() || expressionSql.isBlank()) {
			throw new IllegalArgumentException("CHECK name and expression are required");
		}
	}
}