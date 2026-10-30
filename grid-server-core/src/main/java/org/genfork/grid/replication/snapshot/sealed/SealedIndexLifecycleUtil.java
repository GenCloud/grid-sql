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
package org.genfork.grid.replication.snapshot.sealed;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Function;

import org.genfork.grid.mem.index.GridCompositeIndex;
import org.genfork.grid.mem.stage.GridEntriesProcessor;

/**
 * Single choke-point for sealed index query-path detach before close/replace.
 * <p>
 * Order: {@link #detachQuerySealedIndexes} then {@code releaseShard}/{@code deleteIndex}/{@code closeAll}.
 * Bind/install of new readers happens only after open (caller).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class SealedIndexLifecycleUtil {
	private SealedIndexLifecycleUtil() {
	}

	/**
	 * Drop sealed fallback wrappers from the query/index map (RAM delegate only).
	 * Also clears optimized plan cache inside {@link GridCompositeIndex#clearSealedIndexes()}.
	 */
	public static void detachQuerySealedIndexes(GridCompositeIndex index) {
		if (index == null) {
			return;
		}
		index.clearSealedIndexes();
	}

	/**
	 * Detach query sealed refs for the processor's composite index, then release all
	 * {@code .sbpt} readers for {@code domain#shard}.
	 */
	public static void detachAndReleaseShard(
			GridEntriesProcessor processor,
			SealedBPTreeService bptreeService,
			String domainType,
			int shard
	) throws IOException {
		Objects.requireNonNull(domainType, "domainType");
		if (processor != null) {
			detachQuerySealedIndexes(processor.compositeIndex());
		}
		if (bptreeService != null) {
			bptreeService.releaseShard(domainType, shard);
		}
	}

	/**
	 * Resolve processor via {@code byShard}, then {@link #detachAndReleaseShard}.
	 */
	public static void detachAndReleaseShard(
			Function<Integer, GridEntriesProcessor> byShard,
			SealedBPTreeService bptreeService,
			String domainType,
			int shard
	) throws IOException {
		final GridEntriesProcessor processor = byShard == null ? null : byShard.apply(shard);
		detachAndReleaseShard(processor, bptreeService, domainType, shard);
	}

	/**
	 * Detach sealed fallbacks for every domain processor before closing all {@code .sbpt} readers.
	 */
	public static void detachAllDomainsThenCloseBptree(
			Iterable<GridEntriesProcessor> domainProcessors,
			SealedBPTreeService bptreeService
	) {
		if (domainProcessors != null) {
			for (GridEntriesProcessor processor : domainProcessors) {
				if (processor != null) {
					detachQuerySealedIndexes(processor.compositeIndex());
				}
			}
		}
		if (bptreeService != null) {
			bptreeService.closeAll();
		}
	}
}
