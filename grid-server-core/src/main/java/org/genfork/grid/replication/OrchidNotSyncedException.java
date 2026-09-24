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
package org.genfork.grid.replication;

/**
 * Thrown when ORCHID order parameter is below threshold (cluster not phase-synced).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class OrchidNotSyncedException extends IllegalStateException {
	public OrchidNotSyncedException(String message) {
		super(message);
	}
}
