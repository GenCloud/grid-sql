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

/**
 * Detect sealed reader close races (HELLO unpack / dump swap vs probe get).
 * <p>
 * Shared by {@code .gmap} and {@code .sbpt} soft-miss paths — probe must not surface
 * closed ISE to SQL clients.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class SealedClosedRaceUtil {
	private SealedClosedRaceUtil() {
	}

	/**
	 * {@code true} when failure is a closed sealed reader race (map or BPTree message prefix only).
	 */
	public static boolean isClosedReaderFailure(Throwable error) {
		Throwable cursor = error;
		while (cursor != null) {
			final String message = cursor.getMessage();
			if (message != null
					&& (message.startsWith(SealedGridMapReader.CLOSED_READER_MESSAGE_PREFIX)
					|| message.startsWith(SealedBPTreeReader.CLOSED_READER_MESSAGE_PREFIX))) {
				return true;
			}
			cursor = cursor.getCause();
		}
		return false;
	}
}
