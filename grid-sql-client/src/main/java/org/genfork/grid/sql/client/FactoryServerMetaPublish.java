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
package org.genfork.grid.sql.client;

/**
 * Policy for publishing AUTH / ERROR {@link ServerMeta} onto factory-global
 * {@link RemoteConnectionFactory#lastServerMeta()}.
 * <p>
 * Never publish {@code applyLagStale} meta (PRIMARY or {@link SessionRole#READ_REPLICA}):
 * lagging AUTH must rotate / FAIL_STALE without overwriting sticky/fresh factory meta.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class FactoryServerMetaPublish {
	private FactoryServerMetaPublish() {
	}

	/**
	 * Whether factory-global last meta should adopt {@code meta}.
	 * <p>
	 * Stale apply-lag peers are never published. Null role is treated as writer-compatible.
	 */
	public static boolean shouldPublish(SessionRole factoryRole, ServerMeta meta) {
		if (meta == null || meta.applyLagStale()) {
			return false;
		}
		return factoryRole == null
				|| factoryRole == SessionRole.PRIMARY
				|| factoryRole.isReadReplica();
	}
}
