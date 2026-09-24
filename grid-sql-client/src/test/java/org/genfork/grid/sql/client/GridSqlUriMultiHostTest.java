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

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-host grid:// authority parsing.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public class GridSqlUriMultiHostTest {
	@Test
	void parsesCommaSeparatedHostPorts() {
		final GridSqlUri u = GridSqlUri.parse("grid://u:p@h1:15432,h2:15433/public");
		assertEquals("u", u.user());
		assertEquals("p", u.password());
		assertEquals("public", u.schema());
		assertEquals("h1", u.host());
		assertEquals(15432, u.port());
		final List<HostEndpoint> eps = u.endpoints();
		assertEquals(2, eps.size());
		assertEquals(new HostEndpoint("h1", 15432), eps.get(0));
		assertEquals(new HostEndpoint("h2", 15433), eps.get(1));
	}

	@Test
	void rejectsHostsQueryParam() {
		final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> GridSqlUri.parse("grid://h1:15432/public?hosts=h2:15433"));
		assertTrue(ex.getMessage().contains("hosts"));
	}

	@Test
	void singleHostStillWorksWithOptions() {
		final GridSqlUri u = GridSqlUri.parse(
				"grid://127.0.0.1:15432/public?connectTimeoutMs=1000&retryMode=FIXED&maxRetries=2");
		assertEquals(1, u.endpoints().size());
		assertEquals(Duration.ofSeconds(1), u.options().connectTimeout());
		assertEquals(RetryMode.FIXED, u.options().retryMode());
		assertEquals(2, u.options().maxRetries());
	}
}