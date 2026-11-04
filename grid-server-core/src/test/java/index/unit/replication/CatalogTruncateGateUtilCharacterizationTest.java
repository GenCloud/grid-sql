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
package index.unit.replication;

import org.genfork.grid.replication.util.CatalogTruncateGateUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code _catalog} OpLog truncate voter-tip gate.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class CatalogTruncateGateUtilCharacterizationTest {
	@Test
	void soloAllowsWhenLocalAtTip() {
		assertTrue(CatalogTruncateGateUtil.allowTruncate(5L, 5L, 0L, 0));
		assertEquals(5L, CatalogTruncateGateUtil.safeTruncateThrough(5L, 5L, 0L, 0));
	}

	@Test
	void multiVoterDeniesUnknownPeerAck() {
		assertFalse(CatalogTruncateGateUtil.allowTruncate(5L, 5L, 0L, 2));
		assertEquals(0L, CatalogTruncateGateUtil.safeTruncateThrough(5L, 5L, 0L, 2));
	}

	@Test
	void multiVoterAllowsWhenMinAckAtTip() {
		assertTrue(CatalogTruncateGateUtil.allowTruncate(9L, 9L, 9L, 2));
		assertFalse(CatalogTruncateGateUtil.allowTruncate(9L, 9L, 8L, 2));
		assertFalse(CatalogTruncateGateUtil.allowTruncate(9L, 8L, 9L, 2));
	}
}
