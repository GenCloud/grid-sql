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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit checks for {@link WriterFenceSignals}.
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
class WriterFenceSignalsTest {

	@Test
	void detectsRegionFenced() {
		assertTrue(WriterFenceSignals.requiresWriterRediscover(
				new IllegalStateException("region fenced: stale epoch")));
	}

	@Test
	void detectsOrchidTypeName() {
		assertTrue(WriterFenceSignals.requiresWriterRediscover(
				new RuntimeException(WriterFenceSignals.ORCHID_NOT_SYNCED)));
	}

	@Test
	void ignoresUnrelated() {
		assertFalse(WriterFenceSignals.requiresWriterRediscover(
				new IllegalArgumentException("syntax error near SELECT")));
	}
}