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

/**
 * Referential action for {@code ON DELETE} / {@code ON UPDATE} foreign keys.
 *
 * @author: GenCloud
 * @date: 2025/07
 * @since: 1.0
 */
public enum FkAction {
	RESTRICT,
	CASCADE,
	SET_NULL;

	public static final String TOKEN_RESTRICT = "RESTRICT";
	public static final String TOKEN_CASCADE = "CASCADE";
	public static final String TOKEN_SET_NULL = "SET NULL";

	public static FkAction fromToken(String token) {
		if (token == null || token.isBlank()) {
			return RESTRICT;
		}
		final String t = token.trim().toUpperCase();
		return switch (t) {
			case TOKEN_RESTRICT -> RESTRICT;
			case TOKEN_CASCADE -> CASCADE;
			case "SETNULL", TOKEN_SET_NULL -> SET_NULL;
			default -> throw new IllegalArgumentException("Unsupported FK action: " + token);
		};
	}

	public String sqlToken() {
		return switch (this) {
			case RESTRICT -> TOKEN_RESTRICT;
			case CASCADE -> TOKEN_CASCADE;
			case SET_NULL -> TOKEN_SET_NULL;
		};
	}
}
