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
package org.genfork.grid.context.config.health;

import java.util.Map;

import org.genfork.grid.context.config.GridConfigurationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Readiness detail keys for sealed/WS/promote ops transparency (no coordinator).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class GridReadinessHealthIndicatorTest {

	@Test
	void soloWithoutCoordinatorExposesNaPromoteAndHydrate() {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getSqlServer().setEnabled(false);
		final GridReadinessHealthIndicator indicator = new GridReadinessHealthIndicator(
				props,
				emptyProvider(),
				emptyProvider());
		final Health health = indicator.health();
		assertEquals(Status.UP, health.getStatus());
		final Map<String, Object> details = health.getDetails();
		assertEquals("n/a", details.get(GridReadinessHealthIndicator.DETAIL_PROMOTE_HINT));
		assertEquals("n/a", details.get(GridReadinessHealthIndicator.DETAIL_HYDRATE_MODE_CONFIGURED));
		assertEquals("n/a", details.get(GridReadinessHealthIndicator.DETAIL_REGION_EPOCH));
		assertTrue(details.containsKey(GridReadinessHealthIndicator.DETAIL_HYDRATE_SHARDS_DONE));
	}

	private static <T> ObjectProvider<T> emptyProvider() {
		return new ObjectProvider<>() {
			@Override
			public T getObject(Object... args) {
				return null;
			}

			@Override
			public T getIfAvailable() {
				return null;
			}

			@Override
			public T getIfUnique() {
				return null;
			}

			@Override
			public T getObject() {
				return null;
			}
		};
	}
}