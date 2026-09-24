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
package org.genfork.grid.sql.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Connection URL timezone= option.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
class GridSqlUriTimezoneTest {
	@Test
	void parsesTimezoneQueryParam() {
		final GridSqlUri uri = GridSqlUri.parse(
				"grid://u:p@h1:15432/public?timezone=Europe/Moscow");
		assertEquals("Europe/Moscow", uri.options().timezone());
	}

	@Test
	void defaultTimezoneIsUtc() {
		final GridSqlUri uri = GridSqlUri.parse("grid://u:p@h1:15432/public");
		assertEquals(ConnectionOptions.DEFAULT_TIMEZONE, uri.options().timezone());
	}
}