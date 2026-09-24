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

import java.util.Collections;
import java.util.List;

import org.genfork.grid.query.util.QueryChunkMerge;
import org.genfork.grid.query.util.SetOpKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit coverage for {@link QueryChunkMerge#applySetOp} INTERSECT / EXCEPT.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
class QueryChunkMergeSetOpTest {
	@Test
	void intersectDistinct() {
		final List<Object[]> left = List.of(row(1), row(2), row(2));
		final List<Object[]> right = List.of(row(2), row(3));
		final List<Object[]> out = QueryChunkMerge.applySetOp(
				left, right, SetOpKind.INTERSECT, false, 0);
		assertEquals(1, out.size());
		assertEquals(2, out.getFirst()[0]);
	}

	@Test
	void exceptAll() {
		final List<Object[]> left = List.of(row(7), row(7), row(7));
		final List<Object[]> right = Collections.singletonList(row(7));
		final List<Object[]> out = QueryChunkMerge.applySetOp(
				left, right, SetOpKind.EXCEPT, true, 0);
		assertEquals(2, out.size());
	}

	@Test
	void unionThenIntersectLeftFold() {
		final List<Object[]> a = List.of(row(1), row(2));
		final List<Object[]> b = List.of(row(2), row(3));
		final List<Object[]> c = List.of(row(2), row(4));
		final List<Object[]> ab = QueryChunkMerge.applySetOp(a, b, SetOpKind.UNION, false, 0);
		final List<Object[]> out = QueryChunkMerge.applySetOp(ab, c, SetOpKind.INTERSECT, false, 0);
		assertEquals(1, out.size());
		assertEquals(2, out.getFirst()[0]);
	}

	private static Object[] row(int id) {
		return new Object[]{id};
	}
}