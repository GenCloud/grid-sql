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

import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.replication.util.OpLogStreamKeyUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Package-local: LAZY {@code ensureShardHydrated} with no domain applier must not mark hydrated.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class LazyShardHydrateApplierNullTest {
	private static final String DOMAIN = "no_applier_lazy";

	@TempDir
	Path tmp;

	@Test
	void ensureShardHydratedWithoutApplierDoesNotMarkDone() {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("repl").toString());
		props.getReplication().setClusterId("c1");
		props.getReplication().setNodeId("n1");

		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		try {
			final SealedHydrateService hydrate = coord.sealedHydrateService();
			assertNotNull(hydrate);
			final String key = OpLogStreamKeyUtil.format(DOMAIN, 0);
			assertDoesNotThrow(() -> coord.ensureShardHydrated(DOMAIN, 0));
			assertFalse(hydrate.hydratedShards.contains(key),
					"applier==null must not falsely mark shard hydrated");
		} finally {
			coord.stop();
		}
	}
}
