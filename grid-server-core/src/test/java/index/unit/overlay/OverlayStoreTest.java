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
package index.unit.overlay;

import org.genfork.grid.overlay.OverlayStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-memory overlay put/get.
 *
 * @author: GenCloud
 * @date: 2026/02
 * @since: 1.0
 */
public class OverlayStoreTest {
	@Test
	void putAndGetWhenEnabled() {
		final OverlayStore store = new OverlayStore(true);
		final byte[] key = new byte[]{1, 2, 3};
		store.put("demo.D", key, 1000L, true, "hot");
		assertTrue(store.get("demo.D", key).isPresent());
		assertEquals("hot", store.get("demo.D", key).orElseThrow().qosTag());
		assertTrue(store.get("demo.D", key).orElseThrow().pin());
	}

	@Test
	void disabledIsNoOp() {
		final OverlayStore store = new OverlayStore(false);
		store.put("demo.D", new byte[]{1}, 1L, true, "x");
		assertTrue(store.get("demo.D", new byte[]{1}).isEmpty());
		assertEquals(0, store.size());
	}
}
