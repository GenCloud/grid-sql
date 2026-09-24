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

import java.util.Objects;

/**
 * SQL TCP endpoint ({@code host:port}) for single- or multi-host {@code grid://} URLs.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public record HostEndpoint(String host, int port) {
	private static final char HOST_PORT_SEP = ':';

	public HostEndpoint {
		Objects.requireNonNull(host, "host");
		if (host.isBlank()) {
			throw new IllegalArgumentException("host blank");
		}
		if (port <= 0 || port > 65_535) {
			throw new IllegalArgumentException("invalid port: " + port);
		}
	}

	@Override
	public String toString() {
		return host + HOST_PORT_SEP + port;
	}
}