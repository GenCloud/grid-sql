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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Factory-global {@link ServerMeta} publish policy (READ_REPLICA stale skip).
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public class FactoryServerMetaPublishTest {
	private static final String NODE = "n1";
	private static final long SCHEMA_EPOCH = 1L;

	@Test
	void nullMetaNeverPublishes() {
		assertFalse(FactoryServerMetaPublish.shouldPublish(SessionRole.PRIMARY, null));
		assertFalse(FactoryServerMetaPublish.shouldPublish(SessionRole.READ_REPLICA, null));
	}

	@Test
	void primaryPublishesStaleAndFresh() {
		assertTrue(FactoryServerMetaPublish.shouldPublish(SessionRole.PRIMARY, freshMeta()));
		assertTrue(FactoryServerMetaPublish.shouldPublish(SessionRole.PRIMARY, staleMeta()));
	}

	@Test
	void readReplicaSkipsStaleOnly() {
		assertTrue(FactoryServerMetaPublish.shouldPublish(SessionRole.READ_REPLICA, freshMeta()));
		assertFalse(FactoryServerMetaPublish.shouldPublish(SessionRole.READ_REPLICA, staleMeta()));
	}

	@Test
	void nullRoleTreatsAsWriterPublish() {
		assertTrue(FactoryServerMetaPublish.shouldPublish(null, staleMeta()));
	}

	private static ServerMeta freshMeta() {
		return new ServerMeta(NODE, false, "", false, SCHEMA_EPOCH);
	}

	private static ServerMeta staleMeta() {
		return new ServerMeta(NODE, false, "", true, SCHEMA_EPOCH);
	}
}
