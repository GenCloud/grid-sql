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

import org.genfork.grid.query.distributed.DistributedQueryExecutor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Distributed fan-out: LIMIT push-down + multi-shard merge correctness.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class DistributedQueryExecutorTest {
	@Test
	void mergeAppliesLimitAfterUnionAll() {
		final List<byte[]> merged = DistributedQueryExecutor.execute(
				"SELECT * FROM T",
				3,
				true,
				sql -> List.of(b("a"), b("b")),
				List.of(sql -> List.of(b("c"), b("d")))
		);
		assertEquals(3, merged.size());
		assertEquals("a", new String(merged.get(0), StandardCharsets.UTF_8));
		assertEquals("c", new String(merged.get(2), StandardCharsets.UTF_8));
	}

	@Test
	void pushesLimitWhenAbsentAndEarlyStopsPeers() {
		final List<String> seen = new ArrayList<>();
		final AtomicInteger peerCalls = new AtomicInteger();

		final List<byte[]> merged = DistributedQueryExecutor.execute(
				"SELECT * FROM T WHERE active = 1",
				2,
				true,
				sql -> {
					seen.add(sql);
					return List.of(b("a"), b("b"), b("c"));
				},
				List.of(
						sql -> {
							peerCalls.incrementAndGet();
							seen.add(sql);
							return List.of(b("d"));
						},
						sql -> {
							peerCalls.incrementAndGet();
							seen.add(sql);
							return List.of(b("e"));
						}
				)
		);

		assertEquals(2, merged.size());
		assertEquals(0, peerCalls.get());
		assertTrue(seen.getFirst().toUpperCase().contains("LIMIT 2"));
	}

	@Test
	void multiShardFanOutReturnsCorrectLimitedRows() {
		final List<String> peerSql = new ArrayList<>();
		final AtomicInteger peer2Calls = new AtomicInteger();

		final Function<String, List<byte[]>> peer1 = sql -> {
			peerSql.add(sql);
			return List.of(b("p1a"), b("p1b"), b("p1c"));
		};
		final Function<String, List<byte[]>> peer2 = sql -> {
			peer2Calls.incrementAndGet();
			peerSql.add(sql);
			return List.of(b("p2a"), b("p2b"));
		};

		final List<byte[]> merged = DistributedQueryExecutor.execute(
				"SELECT id FROM orders",
				5,
				true,
				sql -> List.of(b("l1"), b("l2")),
				List.of(peer1, peer2)
		);

		assertEquals(5, merged.size());
		assertEquals("l1", new String(merged.get(0), StandardCharsets.UTF_8));
		assertEquals("l2", new String(merged.get(1), StandardCharsets.UTF_8));
		assertEquals("p1a", new String(merged.get(2), StandardCharsets.UTF_8));
		assertEquals("p1c", new String(merged.get(4), StandardCharsets.UTF_8));
		assertEquals(0, peer2Calls.get());
		assertFalse(peerSql.isEmpty());
		assertTrue(peerSql.getFirst().contains(DistributedQueryExecutor.LIMIT_CLAUSE_PREFIX.trim())
				|| peerSql.getFirst().contains("LIMIT 3"));
		assertTrue(peerSql.getFirst().contains("LIMIT 3"));
	}

	@Test
	void withPushedLimitAppendsWhenRequested() {
		assertEquals(
				"SELECT * FROM T" + DistributedQueryExecutor.LIMIT_CLAUSE_PREFIX + "3",
				DistributedQueryExecutor.withPushedLimit("SELECT * FROM T", 3));
	}

	@Test
	void executeDoesNotPushWhenFlagFalse() {
		final List<String> seen = new ArrayList<>();
		DistributedQueryExecutor.execute(
				"SELECT * FROM T",
				3,
				false,
				sql -> {
					seen.add(sql);
					return List.of(b("a"));
				},
				List.of()
		);
		assertEquals("SELECT * FROM T", seen.getFirst());
	}

	private static byte[] b(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}
}
