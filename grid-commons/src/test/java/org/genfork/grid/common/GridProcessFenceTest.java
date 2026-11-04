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
package org.genfork.grid.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Unit checks for {@link GridProcessFence} (process-global; no reset hook).
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
@ResourceLock(value = "grid-process-fence", mode = ResourceAccessMode.READ_WRITE)
class GridProcessFenceTest {

	@Test
	void tripOnceKeepsFirstReasonAndBlocksAdmit() {
		assumeFalse(GridProcessFence.isTripped(), "fence already tripped in this JVM");
		assertTrue(GridProcessFence.trip("first"));
		assertFalse(GridProcessFence.trip("second"));
		assertTrue(GridProcessFence.isTripped());
		assertEquals("first", GridProcessFence.reasonOrEmpty());
		assertThrows(IllegalStateException.class, () -> GridProcessFence.checkAdmit("test"));
	}
}
