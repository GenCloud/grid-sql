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
package org.genfork.grid.threading;

import com.google.common.annotations.VisibleForTesting;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test-only park helper so {@link CommonPoolGuard} sampler can observe a grid frame
 * that is not on {@link CommonPoolGuard} itself (guard frames are filtered).
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public final class CommonPoolGuardProbe {

	private CommonPoolGuardProbe() {
	}

	@VisibleForTesting
	public static void hold(CountDownLatch entered, CountDownLatch release, long waitMs) {
		entered.countDown();
		try {
			release.await(waitMs, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}