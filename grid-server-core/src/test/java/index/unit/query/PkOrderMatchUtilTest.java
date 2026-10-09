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

import org.genfork.grid.query.plan.PkOrderMatchUtil;
import org.genfork.grid.query.plan.SortOrderData;
import org.genfork.grid.query.plan.SortOrderData.OrderDirection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PRIMARY KEY ascending-prefix ORDER BY detection.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
public class PkOrderMatchUtilTest {
	@Test
	void fullCompositePkAscendingMatches() {
		assertTrue(PkOrderMatchUtil.matchesAscendingPrimaryKeyPrefix(
				new SortOrderData[]{
						new SortOrderData("server_id", OrderDirection.ASC),
						new SortOrderData("biset_type", OrderDirection.ASC)
				},
				List.of("server_id", "biset_type")));
	}

	@Test
	void leadingPkPrefixMatches() {
		assertTrue(PkOrderMatchUtil.matchesAscendingPrimaryKeyPrefix(
				new SortOrderData[]{new SortOrderData("server_id", OrderDirection.ASC)},
				List.of("server_id", "biset_type")));
	}

	@Test
	void descOrWrongColumnRejected() {
		assertFalse(PkOrderMatchUtil.matchesAscendingPrimaryKeyPrefix(
				new SortOrderData[]{new SortOrderData("server_id", OrderDirection.DESC)},
				List.of("server_id", "biset_type")));
		assertFalse(PkOrderMatchUtil.matchesAscendingPrimaryKeyPrefix(
				new SortOrderData[]{new SortOrderData("biset_type", OrderDirection.ASC)},
				List.of("server_id", "biset_type")));
	}
}
