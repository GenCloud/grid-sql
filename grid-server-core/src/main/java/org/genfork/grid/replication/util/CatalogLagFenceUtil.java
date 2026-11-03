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
package org.genfork.grid.replication.util;

/**
 * Catalog apply-lag fence predicates (stuck readiness vs transient lag vs AUTH/DDL).
 * <p>
 * Transient {@code lag > 0} is normal (journal before apply). Readiness DOWN only when
 * lag is stuck without applied progress. AUTH folds any catalog lag into {@code applyLagStale}
 * (sticky rotate) — never {@code AUTH_FAILED}. DDL mint denies while lagging or behind peer
 * HELLO {@code catalogAppliedEpoch}.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class CatalogLagFenceUtil {

	/** No applied progress for this long with lag&gt;0 flips readiness DOWN. */
	public static final long CATALOG_LAG_STUCK_MS = 5_000L;

	private CatalogLagFenceUtil() {
	}

	/**
	 * AUTH / write stale: any positive catalog apply lag (client sticky rotates).
	 */
	public static boolean markApplyLagStale(long catalogApplyLag) {
		return catalogApplyLag > 0L;
	}

	/**
	 * DDL mint deny when local catalog trailing tip or behind max peer applied epoch.
	 */
	public static boolean denyDdlMint(long catalogApplyLag, long localApplied, long maxPeerCatalogApplied) {
		if (catalogApplyLag > 0L) {
			return true;
		}
		return maxPeerCatalogApplied > localApplied;
	}

	/**
	 * Readiness DOWN only when lag&gt;0 and applied watermark has not advanced for stuck window.
	 */
	public static boolean readinessDownForStuckLag(
			long catalogApplyLag,
			long millisSinceAppliedProgress,
			long stuckMs
	) {
		if (catalogApplyLag <= 0L) {
			return false;
		}
		final long window = stuckMs <= 0L ? CATALOG_LAG_STUCK_MS : stuckMs;
		return millisSinceAppliedProgress >= window;
	}

	/**
	 * Tracks last applied watermark progress for stuck detection.
	 */
	public static final class ProgressTracker {
		private long lastApplied = -1L;
		private long lastProgressMillis = System.currentTimeMillis();

		public synchronized void noteApplied(long appliedWatermark) {
			if (appliedWatermark < 0L) {
				return;
			}
			if (appliedWatermark != lastApplied) {
				lastApplied = appliedWatermark;
				lastProgressMillis = System.currentTimeMillis();
			}
		}

		public synchronized long millisSinceProgress(long nowMillis) {
			return Math.max(0L, nowMillis - lastProgressMillis);
		}
	}
}
