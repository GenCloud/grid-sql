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
package org.genfork.grid.replication.region;

/**
 * Quorum math for Hold/Witness claim ACKs (exclude self-only majorities).
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public final class RegionClaimQuorum {
	private static final int MIN_VOTERS_FOR_MAJORITY = 1;

	private RegionClaimQuorum() {
	}

	/**
	 * Required ACK count among {@code voterCount} Hold/Witness peers (not including a lone self).
	 */
	public static int requiredAcks(int voterCount) {
		if (voterCount < MIN_VOTERS_FOR_MAJORITY) {
			return MIN_VOTERS_FOR_MAJORITY;
		}
		return (voterCount / 2) + 1;
	}

	public static boolean hasQuorum(int ackCount, int voterCount) {
		return ackCount >= requiredAcks(voterCount);
	}
}