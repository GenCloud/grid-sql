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
package index.unit.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.genfork.grid.query.adaptive.AdaptiveChunkScheduler;
import org.genfork.grid.query.adaptive.AdaptiveParallelScan;
import org.genfork.grid.query.adaptive.HeavyQueryAdmission;
import org.genfork.grid.query.adaptive.QueryHeaviness;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.threading.ThreadService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mid-flight chunk re-split / coalesce helpers for AQE v2.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class AdaptiveChunkSchedulerIT {
	private static final int WORKERS = 4;
	private static final int HEX_CHARS_PER_BYTE = 2;
	private static final int KEY_COUNT = AdaptiveChunkScheduler.resplitRemainingFloor() * 2;
	private static final int COALESCE_TARGET = 2;
	private static final int COALESCE_OVER_FACTOR_CHUNKS = COALESCE_TARGET * 2 + 1;
	private static final boolean NOT_INDEXED = false;
	private static final boolean FULLY_INDEXED = true;
	private static final int BELOW_RESPLIT_FLOOR = AdaptiveChunkScheduler.resplitRemainingFloor() - 1;

	@BeforeEach
	void setUp() {
		ThreadService.ensureRunning();
	}

	@Test
	void shouldResplitThresholds() {
		assertTrue(AdaptiveChunkScheduler.shouldResplit(
				AdaptiveChunkScheduler.resplitRemainingFloor(),
				1,
				WORKERS
		));
		assertFalse(AdaptiveChunkScheduler.shouldResplit(BELOW_RESPLIT_FLOOR, 1, WORKERS));
		assertFalse(AdaptiveChunkScheduler.shouldResplit(
				AdaptiveChunkScheduler.resplitRemainingFloor(),
				WORKERS,
				WORKERS
		));
	}

	@Test
	void shouldCoalesceThresholds() {
		assertTrue(AdaptiveChunkScheduler.shouldCoalesce(COALESCE_OVER_FACTOR_CHUNKS, COALESCE_TARGET));
		assertFalse(AdaptiveChunkScheduler.shouldCoalesce(COALESCE_TARGET, COALESCE_TARGET));
	}

	@Test
	void resplitAndCoalescePreserveAllKeys() {
		final List<byte[]> keys = wireKeys(KEY_COUNT);
		final List<List<byte[]>> resplit = AdaptiveChunkScheduler.resplit(keys, WORKERS);
		assertEquals(sortedFingerprint(keys), sortedFingerprint(flatten(resplit)));

		final List<List<byte[]>> many = AdaptiveChunkScheduler.resplit(keys, COALESCE_OVER_FACTOR_CHUNKS);
		final List<List<byte[]>> coalesced = AdaptiveChunkScheduler.coalesce(many, COALESCE_TARGET);
		assertEquals(sortedFingerprint(keys), sortedFingerprint(flatten(coalesced)));
		assertTrue(coalesced.size() <= Math.max(COALESCE_TARGET, 1));
	}

	@Test
	void midFlightParallelEquivalenceWithLargeKeyCount() {
		final List<byte[]> keys = wireKeys(KEY_COUNT);
		final QueryHeaviness heavy = QueryHeaviness.estimate(
				QueryHeaviness.heavyCandidateThreshold(),
				NOT_INDEXED
		);
		final Function<List<byte[]>, List<byte[]>> mapper = chunk -> new ArrayList<>(chunk);
		final HeavyQueryAdmission admission = new HeavyQueryAdmission(WORKERS, WORKERS);

		final List<byte[]> parallel = AdaptiveParallelScan.mapMergeKeys(keys, mapper, heavy, admission);
		final List<byte[]> serial = AdaptiveParallelScan.mapMergeKeys(
				keys,
				mapper,
				QueryHeaviness.estimate(0, FULLY_INDEXED),
				admission
		);
		assertEquals(KEY_COUNT, parallel.size());
		assertEquals(sortedFingerprint(serial), sortedFingerprint(parallel));
	}

	private static List<byte[]> wireKeys(int count) {
		final List<byte[]> keys = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			keys.add(SqlWireUtil.toGenericArray(i));
		}
		return keys;
	}

	private static List<byte[]> flatten(List<List<byte[]>> chunks) {
		final List<byte[]> flat = new ArrayList<>();
		for (List<byte[]> chunk : chunks) {
			if (chunk != null) {
				flat.addAll(chunk);
			}
		}
		return flat;
	}

	private static String sortedFingerprint(List<byte[]> keys) {
		final List<byte[]> copy = new ArrayList<>(keys);
		copy.sort(Comparator.comparing(AdaptiveChunkSchedulerIT::hex));
		final StringBuilder sb = new StringBuilder();
		for (byte[] key : copy) {
			sb.append(hex(key)).append(',');
		}
		return sb.toString();
	}

	private static String hex(byte[] bytes) {
		final StringBuilder sb = new StringBuilder(bytes.length * HEX_CHARS_PER_BYTE);
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}
}
