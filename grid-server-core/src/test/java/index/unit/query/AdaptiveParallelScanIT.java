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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
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
 * Adaptive parallel scan (AQE admission) vs serial equivalence on wire keys.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class AdaptiveParallelScanIT {
	private static final int KEY_COUNT = 256;
	private static final int WORKERS = 4;
	private static final int SATURATED_MAX_CONCURRENT = 1;
	private static final boolean NOT_INDEXED = false;
	private static final boolean FULLY_INDEXED = true;
	private static final int INDEXED_OVER_THRESHOLD_FACTOR = 2;
	private static final int SERIAL_MAPPER_INVOCATIONS = 1;
	private static final int HEX_CHARS_PER_BYTE = 2;
	private static final int MID_FLIGHT_KEY_FACTOR = 2;

	private HeavyQueryAdmission admission;

	@BeforeEach
	void setUp() {
		ThreadService.ensureRunning();
		admission = new HeavyQueryAdmission(SATURATED_MAX_CONCURRENT, WORKERS);
		admission.resetInflightForTests();
	}

	@AfterEach
	void tearDown() {
		admission.resetInflightForTests();
	}

	@Test
	void parallelAndSerialProduceSameWireKeySet() {
		final List<byte[]> keys = wireKeys(KEY_COUNT);
		final QueryHeaviness heavy = QueryHeaviness.estimate(
				QueryHeaviness.heavyCandidateThreshold(),
				NOT_INDEXED
		);
		final Function<List<byte[]>, List<byte[]>> mapper = chunk -> {
			final List<byte[]> out = new ArrayList<>(chunk.size());
			for (byte[] key : chunk) {
				out.add(key);
			}
			return out;
		};

		final HeavyQueryAdmission parallelAdmission = new HeavyQueryAdmission(WORKERS, WORKERS);
		final List<byte[]> parallel = AdaptiveParallelScan.mapMergeKeys(keys, mapper, heavy, parallelAdmission);
		final List<byte[]> serial = AdaptiveParallelScan.mapMergeKeys(
				keys,
				mapper,
				QueryHeaviness.estimate(0, FULLY_INDEXED),
				parallelAdmission
		);

		assertEquals(KEY_COUNT, parallel.size());
		assertEquals(KEY_COUNT, serial.size());
		assertEquals(sortedFingerprint(serial), sortedFingerprint(parallel));
	}

	@Test
	void admissionSaturatedFallsBackToSerial() {
		final List<byte[]> keys = wireKeys(KEY_COUNT);
		final QueryHeaviness heavy = QueryHeaviness.estimate(
				QueryHeaviness.heavyCandidateThreshold(),
				NOT_INDEXED
		);
		assertTrue(admission.tryAcquire(), "pre-saturate admission");
		assertFalse(admission.tryAcquire(), "quota saturated");

		final AtomicInteger mapperInvocations = new AtomicInteger();
		final Function<List<byte[]>, List<byte[]>> mapper = chunk -> {
			mapperInvocations.incrementAndGet();
			return new ArrayList<>(chunk);
		};

		final List<byte[]> out = AdaptiveParallelScan.mapMergeKeys(keys, mapper, heavy, admission);
		assertEquals(KEY_COUNT, out.size());
		assertEquals(SERIAL_MAPPER_INVOCATIONS, mapperInvocations.get(), "saturated admission must fall back to one serial map");
		assertEquals(SATURATED_MAX_CONCURRENT, admission.inflightHeavy());

		admission.release();
		assertEquals(0, admission.inflightHeavy());
	}

	@Test
	void indexedPlanIsNotHeavy() {
		final QueryHeaviness h = QueryHeaviness.estimate(
				QueryHeaviness.heavyCandidateThreshold() * INDEXED_OVER_THRESHOLD_FACTOR,
				FULLY_INDEXED
		);
		assertFalse(h.isHeavy());
	}

	@Test
	void midFlightLargeKeyCountSerialParallelEquivalence() {
		final int largeCount = AdaptiveChunkScheduler.resplitRemainingFloor() * MID_FLIGHT_KEY_FACTOR;
		final List<byte[]> keys = wireKeys(largeCount);
		final QueryHeaviness heavy = QueryHeaviness.estimate(
				QueryHeaviness.heavyCandidateThreshold(),
				NOT_INDEXED
		);
		final Function<List<byte[]>, List<byte[]>> mapper = chunk -> new ArrayList<>(chunk);
		final HeavyQueryAdmission parallelAdmission = new HeavyQueryAdmission(WORKERS, WORKERS);

		final List<byte[]> parallel = AdaptiveParallelScan.mapMergeKeys(keys, mapper, heavy, parallelAdmission);
		final List<byte[]> serial = AdaptiveParallelScan.mapMergeKeys(
				keys,
				mapper,
				QueryHeaviness.estimate(0, FULLY_INDEXED),
				parallelAdmission
		);
		assertEquals(largeCount, parallel.size());
		assertEquals(sortedFingerprint(serial), sortedFingerprint(parallel));
	}

	private static List<byte[]> wireKeys(int count) {
		final List<byte[]> keys = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			keys.add(SqlWireUtil.toGenericArray(i));
		}
		return keys;
	}

	private static String sortedFingerprint(List<byte[]> keys) {
		final List<byte[]> copy = new ArrayList<>(keys);
		copy.sort(Comparator.comparing(AdaptiveParallelScanIT::hex));
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