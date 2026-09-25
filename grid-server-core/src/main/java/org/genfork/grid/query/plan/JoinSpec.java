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
package org.genfork.grid.query.plan;

import org.genfork.grid.sql.ast.SelectAst.JoinEq;
import org.genfork.grid.sql.ast.SelectAst.JoinKind;

import java.util.List;

/**
 * JOIN … ON leftCol = rightCol [AND …] (INNER / LEFT / RIGHT / FULL OUTER).
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public record JoinSpec(
		String rightTable,
		String rightAlias,
		List<JoinEq> eqs,
		JoinKind kind
) {
	public JoinSpec {
		if (eqs == null || eqs.isEmpty()) {
			throw new IllegalArgumentException("JoinSpec requires at least one equality");
		}
		eqs = List.copyOf(eqs);
	}

	public JoinSpec(String rightTable, String leftColumn, String rightColumn) {
		this(rightTable, null, List.of(new JoinEq(leftColumn, rightColumn)), JoinKind.INNER);
	}

	public JoinSpec(String rightTable, String leftColumn, String rightColumn, JoinKind kind) {
		this(rightTable, null, List.of(new JoinEq(leftColumn, rightColumn)), kind);
	}

	public JoinSpec(String rightTable, String leftColumn, String rightColumn, boolean leftOuter) {
		this(rightTable, null, List.of(new JoinEq(leftColumn, rightColumn)),
				leftOuter ? JoinKind.LEFT : JoinKind.INNER);
	}

	public String leftColumn() {
		return eqs.getFirst().leftCol();
	}

	public String rightColumn() {
		return eqs.getFirst().rightCol();
	}

	public boolean leftOuter() {
		return kind == JoinKind.LEFT || kind == JoinKind.FULL;
	}

	public boolean rightOuter() {
		return kind == JoinKind.RIGHT || kind == JoinKind.FULL;
	}
}
