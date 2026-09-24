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
package org.genfork.grid.replication.log;

import java.util.TreeMap;

/**
 * Refcount helpers for orchid expected-opSeq holdback maps.
 * <p>
 * Same expected seq may be {@code beginJoin}'d twice after {@code failProposeChain}
 * rewind + re-admit; a late {@code cancelJoin} from the failed TX must not drop the
 * surviving join (set-semantics {@code TreeSet#remove} did).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
final class InflightSeqRefCounts {
	private static final int COUNT_ONE = 1;

	private InflightSeqRefCounts() {
	}

	/** Increment join count for {@code expectedOpSeq}; O(log n). */
	static void increment(TreeMap<Long, Integer> counts, long expectedOpSeq) {
		final Integer cur = counts.get(expectedOpSeq);
		if (cur == null) {
			counts.put(expectedOpSeq, COUNT_ONE);
			return;
		}
		counts.put(expectedOpSeq, cur.intValue() + COUNT_ONE);
	}

	/**
	 * Decrement join count; remove key at zero. Returns {@code true} if the map
	 * no longer holds {@code expectedOpSeq}.
	 */
	static boolean decrement(TreeMap<Long, Integer> counts, long expectedOpSeq) {
		final Integer cur = counts.get(expectedOpSeq);
		if (cur == null) {
			return true;
		}
		final int next = cur.intValue() - COUNT_ONE;
		if (next <= 0) {
			counts.remove(expectedOpSeq);
			return true;
		}
		counts.put(expectedOpSeq, next);
		return false;
	}

	/** Drop all join slots for {@code expectedOpSeq} (committed to ready). */
	static void clear(TreeMap<Long, Integer> counts, long expectedOpSeq) {
		counts.remove(expectedOpSeq);
	}

	/** Lowest expected seq still in flight, or {@code null} if empty. */
	static Long lowest(TreeMap<Long, Integer> counts) {
		if (counts.isEmpty()) {
			return null;
		}
		return counts.firstKey();
	}
}