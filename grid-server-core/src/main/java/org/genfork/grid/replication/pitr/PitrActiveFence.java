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
package org.genfork.grid.replication.pitr;

import java.util.Objects;

import com.google.common.annotations.VisibleForTesting;

import org.genfork.grid.replication.region.RegionRole;

/**
 * Active-region fence for coordinated multi-DC PITR restore.
 * <p>
 * Restore under Multi-DC must run only while this site is {@link RegionRole#ACTIVE}
 * and no remote peer is also Active (dual-writer).
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class PitrActiveFence {
	private static final String ERR_NOT_ACTIVE = "PITR multi-DC restore requires Active region role, got ";
	private static final String ERR_DUAL_WRITER = "PITR multi-DC restore refused: dual Active writers";

	private PitrActiveFence() {
	}

	/**
	 * Fail-closed gate before coordinated restore.
	 *
	 * @param localRole            lease role of the site performing restore
	 * @param remotePeerAlsoActive {@code true} when a remote peer currently advertises Active
	 * @throws IllegalStateException when fence is violated
	 */
	public static void requireActiveFence(RegionRole localRole, boolean remotePeerAlsoActive) {
		Objects.requireNonNull(localRole, "localRole");
		if (localRole != RegionRole.ACTIVE) {
			throw new IllegalStateException(ERR_NOT_ACTIVE + localRole);
		}
		if (remotePeerAlsoActive) {
			throw new IllegalStateException(ERR_DUAL_WRITER);
		}
	}

	/**
	 * {@code true} when peer wire role is Active (dual-writer signal).
	 */
	@VisibleForTesting
	public static boolean isActiveWire(byte regionRoleWire) {
		return regionRoleWire == RegionRole.ACTIVE.wireCode();
	}
}
