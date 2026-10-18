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
package org.genfork.grid.replication.apply;

import org.genfork.grid.mem.stage.GridEntriesProcessor;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-VT LAZY OpLog hydrate index delta (map-only install + deferred batch index).
 * <p>
 * Isolated per logic VT so parallel sealed-pack hydrates of one domain cannot share
 * {@link ArrayList} iterators ({@code ConcurrentModificationException} on flush).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
final class ReplicaApplierHydrateSession {
	private final List<GridEntriesProcessor.AddEntry> adds = new ArrayList<>();
	private final List<byte[]> removes = new ArrayList<>();

	List<GridEntriesProcessor.AddEntry> adds() {
		return adds;
	}

	List<byte[]> removes() {
		return removes;
	}

	void clear() {
		adds.clear();
		removes.clear();
	}
}
