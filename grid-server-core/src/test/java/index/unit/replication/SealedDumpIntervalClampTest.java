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

import org.genfork.grid.replication.SealedHydrateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Config clamp for {@code grid.durability.sealed-dump-interval-ms}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
@DisplayName("SealedDumpIntervalClamp")
public class SealedDumpIntervalClampTest {

	@Test
	@DisplayName("clamp_defaultMinAndPassthrough")
	void clamp_defaultMinAndPassthrough() {
		assertEquals(
				SealedHydrateService.SEALED_DUMP_INTERVAL_MS,
				SealedHydrateService.clampSealedDumpIntervalMs(0L));
		assertEquals(
				SealedHydrateService.SEALED_DUMP_INTERVAL_MS,
				SealedHydrateService.clampSealedDumpIntervalMs(-1L));
		assertEquals(
				SealedHydrateService.SEALED_DUMP_INTERVAL_MIN_MS,
				SealedHydrateService.clampSealedDumpIntervalMs(1_000L));
		assertEquals(
				90_000L,
				SealedHydrateService.clampSealedDumpIntervalMs(90_000L));
	}
}
