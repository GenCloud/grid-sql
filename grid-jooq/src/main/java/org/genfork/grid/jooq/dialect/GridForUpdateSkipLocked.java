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
package org.genfork.grid.jooq.dialect;

/**
 * SimplifiedSql lock clause fragment {@code FOR UPDATE SKIP LOCKED}.
 * <p>
 * Host {@code SQLDialect.DEFAULT} already renders jOOQ {@code .forUpdate().skipLocked()}
 * as this text under {@link org.genfork.grid.jooq.GridSQL#installProduct}; this type
 * documents the Grid-owned surface and provides the literal for CustomQueryPart wrappers.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public final class GridForUpdateSkipLocked {
	public static final String SQL = "FOR UPDATE SKIP LOCKED";

	private GridForUpdateSkipLocked() {
	}
}
