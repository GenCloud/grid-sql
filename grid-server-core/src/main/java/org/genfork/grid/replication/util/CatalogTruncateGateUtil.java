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
 * Fail-closed gate for truncating {@code _catalog} OpLog.
 * <p>
 * Truncate only when every live voter's applied watermark is at tip. Stuck voter → defer
 * truncate and heal via journal reconcile (never raise OpLog ceiling by hope).
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class CatalogTruncateGateUtil {
	private CatalogTruncateGateUtil() {
	}

	/**
	 * {@code true} when truncate through {@code tipInclusive} is safe.
	 *
	 * @param tipInclusive      local OpLog tip for {@code _catalog}
	 * @param localApplied      local applied watermark
	 * @param minLiveVoterAck   min peer ACK among live voters ({@code 0} = no peer ACK known)
	 * @param liveVoterCount    number of live peers that must acknowledge tip
	 */
	public static boolean allowTruncate(
			long tipInclusive,
			long localApplied,
			long minLiveVoterAck,
			int liveVoterCount
	) {
		if (tipInclusive <= 0L) {
			return false;
		}
		if (localApplied < tipInclusive) {
			return false;
		}
		if (liveVoterCount <= 0) {
			// Solo / no live peers: local tip caught up is enough.
			return true;
		}
		// Fail-closed: unknown peer ACK (0) must not retire catalog ops under multi-voter.
		if (minLiveVoterAck <= 0L) {
			return false;
		}
		return minLiveVoterAck >= tipInclusive;
	}

	/**
	 * Safe truncate watermark, or {@code 0} when gate denies.
	 */
	public static long safeTruncateThrough(
			long tipInclusive,
			long localApplied,
			long minLiveVoterAck,
			int liveVoterCount
	) {
		if (!allowTruncate(tipInclusive, localApplied, minLiveVoterAck, liveVoterCount)) {
			return 0L;
		}
		return tipInclusive;
	}
}
