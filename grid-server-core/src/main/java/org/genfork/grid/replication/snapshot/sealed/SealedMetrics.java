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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for sealed GMAP / BPTree hot paths (QG tuning).
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class SealedMetrics {
	public static final AtomicLong SEALED_MISS = new AtomicLong();
	public static final AtomicLong SEALED_WINDOW_REMAP = new AtomicLong();
	/** Sticky full-payload map skipped because payload exceeds {@code STICKY_MAX_PAYLOAD_BYTES}. */
	public static final AtomicLong SEALED_STICKY_REJECTED = new AtomicLong();
	public static final AtomicLong SEAL_FAIL_SIZE = new AtomicLong();
	public static final AtomicLong SEALED_INDEX_HIT = new AtomicLong();
	public static final AtomicLong SEALED_INDEX_MISS = new AtomicLong();
	public static final AtomicLong SEALED_INDEX_PAGE_FAULT = new AtomicLong();

	private SealedMetrics() {
	}

	public static void reset() {
		SEALED_MISS.set(0);
		SEALED_WINDOW_REMAP.set(0);
		SEALED_STICKY_REJECTED.set(0);
		SEAL_FAIL_SIZE.set(0);
		SEALED_INDEX_HIT.set(0);
		SEALED_INDEX_MISS.set(0);
		SEALED_INDEX_PAGE_FAULT.set(0);
	}
}
