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

import org.genfork.grid.replication.util.SealedIntegrityProbeCacheUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast-path skip gates for sealed integrity sensor.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class SealedIntegrityProbeCacheUtilTest {
	private static final long FP = 42L;

	@Test
	void skipWhenMatchedNotSlow() {
		assertTrue(SealedIntegrityProbeCacheUtil.canSkipStructuralDiskProbe(
				false, Boolean.FALSE, FP, FP));
	}

	@Test
	void skipWhenPeerFpZeroNotSlow() {
		// healKeys ≫ peer HELLO inventory — peerFp==0 must not DirectoryStream.
		assertTrue(SealedIntegrityProbeCacheUtil.canSkipStructuralDiskProbe(
				false, null, FP, 0L));
		assertTrue(SealedIntegrityProbeCacheUtil.canSkipStructuralDiskProbe(
				false, null, 0L, 0L));
	}

	@Test
	void noSkipOnSlowCadenceEvenIfPeerFpZero() {
		assertFalse(SealedIntegrityProbeCacheUtil.canSkipStructuralDiskProbe(
				true, Boolean.FALSE, FP, 0L));
		assertFalse(SealedIntegrityProbeCacheUtil.canSkipStructuralDiskProbe(
				true, Boolean.FALSE, FP, FP));
	}

	@Test
	void noSkipWhenLocalMissingButPeerAdvertises() {
		assertFalse(SealedIntegrityProbeCacheUtil.canSkipStructuralDiskProbe(
				false, Boolean.FALSE, 0L, FP));
	}

	@Test
	void noSkipOnFpMismatch() {
		assertFalse(SealedIntegrityProbeCacheUtil.canSkipStructuralDiskProbe(
				false, Boolean.FALSE, FP, FP + 1L));
	}

	@Test
	void skipWhenMatchedEvenIfHoleCachedTrue() {
		assertTrue(SealedIntegrityProbeCacheUtil.canSkipStructuralDiskProbe(
				false, Boolean.TRUE, FP, FP));
	}
}
