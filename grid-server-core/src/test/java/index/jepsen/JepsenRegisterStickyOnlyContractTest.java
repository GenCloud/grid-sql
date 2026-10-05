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
package index.jepsen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Contract: Jepsen register/Knossos path must stay sticky-only.
 * <p>
 * Evidence GHA 37281849691 D/E: assigned-node fallback during kill-dc-a returned
 * :ok stale reads (write 93 then read 43) and broke Knossos.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
class JepsenRegisterStickyOnlyContractTest {

	private static final String CLIENT_REL =
			"benchmarks/jepsen/clojure/src/jamoa_jepsen/client.clj";

	@Test
	void invokeRegisterDelegatesToStickyOnly_noAssignedNodeFallback() throws Exception {
		final Path client = resolveClient();
		final String src = Files.readString(client, StandardCharsets.UTF_8);
		final int reg = src.indexOf("(defn- invoke-register!");
		assertTrue(reg >= 0, "invoke-register! missing");
		final int next = src.indexOf("(defrecord SqlClient", reg);
		assertTrue(next > reg, "SqlClient record after invoke-register!");
		final String body = src.substring(reg, next);
		assertTrue(body.contains("invoke-with-sticky!"),
				"invoke-register! must delegate to invoke-with-sticky!");
		assertFalse(body.contains("(or primary assigned-node)"),
				"assigned-node fallback must not return (PR13 D/E stale :ok)");
		assertFalse(body.contains("(or (discover-proposer clients)\n                         (when (not= assigned-node target) assigned-node))"),
				"connect-miss must not fall back to assigned-node");
	}

	private static Path resolveClient() {
		Path p = Path.of(CLIENT_REL);
		if (Files.isRegularFile(p)) {
			return p;
		}
		p = Path.of("..", CLIENT_REL);
		if (Files.isRegularFile(p)) {
			return p;
		}
		p = Path.of("../..", CLIENT_REL);
		if (Files.isRegularFile(p)) {
			return p;
		}
		fail("cannot find " + CLIENT_REL + " from user.dir=" + System.getProperty("user.dir"));
		return null;
	}
}