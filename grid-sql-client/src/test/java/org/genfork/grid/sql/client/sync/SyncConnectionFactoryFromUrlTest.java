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
package org.genfork.grid.sql.client.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link SyncConnectionFactory#fromUrl} routing + shareKey without live TCP.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
class SyncConnectionFactoryFromUrlTest {
	@Test
	void writerOnlyWhenNoReadEndpoints() {
		final SyncConnectionFactory factory = SyncConnectionFactory.fromUrl(
				"grid://u:p@127.0.0.1:15432/public");
		assertNull(factory.readFactory());
	}

	@Test
	void replicaReadEndpointsCreateReadFactory() {
		final SyncConnectionFactory factory = SyncConnectionFactory.fromUrl(
				"grid://u:p@127.0.0.1:15432/public?readPreference=REPLICA&readEndpoints=127.0.0.1:15433");
		assertNotNull(factory.readFactory());
	}

	@Test
	void sameUrlInternsSameFactory() {
		final String url = "grid://u:p@127.0.0.1:15432/public?fetchWindow=64";
		final SyncConnectionFactory a = SyncConnectionFactory.fromUrl(url);
		final SyncConnectionFactory b = SyncConnectionFactory.fromUrl(url);
		assertSame(a, b);
	}

	@Test
	void readEndpointsChangeShareKey() {
		final SyncConnectionFactory writerOnly = SyncConnectionFactory.fromUrl(
				"grid://u:p@127.0.0.1:15432/public");
		final SyncConnectionFactory withReads = SyncConnectionFactory.fromUrl(
				"grid://u:p@127.0.0.1:15432/public?readPreference=REPLICA&readEndpoints=127.0.0.1:15433");
		assertNotSame(writerOnly, withReads);
	}
}
