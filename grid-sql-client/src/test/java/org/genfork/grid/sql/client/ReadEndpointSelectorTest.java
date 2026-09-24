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

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Least-inflight pick and stale skip for {@link ReadEndpointSelector}.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public class ReadEndpointSelectorTest {

	@Test
	void rejectEmptyEndpoints() {
		assertThrows(IllegalArgumentException.class, () -> new ReadEndpointSelector(List.of()));
		assertThrows(IllegalArgumentException.class, () -> new ReadEndpointSelector(null));
	}

	@Test
	void acquirePicksLeastInflight() {
		final HostEndpoint a = new HostEndpoint("127.0.0.1", 15433);
		final HostEndpoint b = new HostEndpoint("127.0.0.1", 15434);
		final ReadEndpointSelector sel = new ReadEndpointSelector(List.of(a, b));

		final HostEndpoint first = sel.acquire();
		assertEquals(a, first);
		assertEquals(1, sel.inflightAt(0));
		assertEquals(0, sel.inflightAt(1));

		final HostEndpoint second = sel.acquire();
		assertEquals(b, second);
		assertEquals(1, sel.inflightAt(0));
		assertEquals(1, sel.inflightAt(1));

		sel.release(first);
		assertEquals(0, sel.inflightAt(0));

		final HostEndpoint third = sel.acquire();
		assertEquals(a, third);
		assertEquals(1, sel.inflightAt(0));
		assertEquals(1, sel.inflightAt(1));

		sel.release(second);
		sel.release(third);
	}

	@Test
	void acquireSkipsStaleWhenFreshExists() {
		final HostEndpoint a = new HostEndpoint("127.0.0.1", 15433);
		final HostEndpoint b = new HostEndpoint("127.0.0.1", 15434);
		final ReadEndpointSelector sel = new ReadEndpointSelector(List.of(a, b));

		sel.markStale(a, true);
		final HostEndpoint picked = sel.acquire(true);
		assertEquals(b, picked);
		assertEquals(0, sel.inflightAt(0));
		assertEquals(1, sel.inflightAt(1));
		sel.release(picked);
	}

	@Test
	void acquireFallsBackToStaleWhenAllStale() {
		final HostEndpoint a = new HostEndpoint("127.0.0.1", 15433);
		final HostEndpoint b = new HostEndpoint("127.0.0.1", 15434);
		final ReadEndpointSelector sel = new ReadEndpointSelector(List.of(a, b));

		sel.markStale(a, true);
		sel.markStale(b, true);
		final HostEndpoint picked = sel.acquire(true);
		assertEquals(a, picked);
		sel.release(picked);
	}

	@Test
	void rotateAfterFailureMarksStaleAndPicksOther() {
		final HostEndpoint a = new HostEndpoint("127.0.0.1", 15433);
		final HostEndpoint b = new HostEndpoint("127.0.0.1", 15434);
		final ReadEndpointSelector sel = new ReadEndpointSelector(List.of(a, b));

		final HostEndpoint first = sel.acquire();
		final HostEndpoint next = sel.rotateAfterFailure(first);
		assertNotEquals(first, next);
		assertEquals(b, next);
		assertEquals(0, sel.inflightAt(0));
		assertEquals(1, sel.inflightAt(1));
		assertTrue(sel.staleAt(0));
		assertFalse(sel.staleAt(1));
		sel.release(next);
	}
}
