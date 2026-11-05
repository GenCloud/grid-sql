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

import org.genfork.grid.replication.util.SealedInventoryIdentityUtil;
import org.genfork.grid.replication.util.SealedShardInventoryEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Inventory identity hash is order-stable and sensitive to entry changes.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class SealedInventoryIdentityUtilTest {

	@Test
	void orderDoesNotChangeHash() {
		final SealedShardInventoryEntry a = new SealedShardInventoryEntry("a", 0, 11L);
		final SealedShardInventoryEntry b = new SealedShardInventoryEntry("b", 1, 22L);
		assertEquals(
				SealedInventoryIdentityUtil.identityHash(List.of(a, b)),
				SealedInventoryIdentityUtil.identityHash(List.of(b, a)));
	}

	@Test
	void fingerprintChangeAltersHash() {
		final long h1 = SealedInventoryIdentityUtil.identityHash(
				List.of(new SealedShardInventoryEntry("a", 0, 11L)));
		final long h2 = SealedInventoryIdentityUtil.identityHash(
				List.of(new SealedShardInventoryEntry("a", 0, 12L)));
		assertNotEquals(h1, h2);
	}

	@Test
	void emptyIsZero() {
		assertEquals(0L, SealedInventoryIdentityUtil.identityHash(List.of()));
		assertEquals(0L, SealedInventoryIdentityUtil.identityHash(null));
	}
}
