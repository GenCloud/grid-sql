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
package index.benchmarks;

import org.apache.ignite.Ignition;
import org.apache.ignite.client.ClientCache;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.configuration.ClientConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Apache Ignite thin-client put/get latency (compose jamoa-oss-ignite :10800).
 * Fair peer vs Grid remote/product path.
 * Calm host only. Scripts: run-compare-ignite.ps1 / run-compare-peers.ps1.
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
public class IgniteThinCompareHarness {

	private static final int DEFAULT_OPS = 200;
	private static final int DEFAULT_THIN_PORT = 10800;
	private static final String DEFAULT_CACHE = "default";

	@Test
	void compareIgniteViaThinClient() throws Exception {
		Assumptions.assumeTrue(reachable("127.0.0.1", Integer.parseInt(env("IGNITE_THIN_PORT", "10800"))),
				"Ignite thin :10800 not up — start benchmarks/oss-peers compose");
		runAndWrite();
	}

	public static void main(String[] args) throws Exception {
		runAndWrite();
	}

	static void runAndWrite() throws Exception {
		final int ops = Integer.parseInt(env("COMPARE_OPS", Integer.toString(DEFAULT_OPS)));
		final String stamp = env("JMH_STAMP", java.time.LocalDate.now().toString());
		final String host = env("IGNITE_THIN_HOST", "127.0.0.1");
		final int port = Integer.parseInt(env("IGNITE_THIN_PORT", Integer.toString(DEFAULT_THIN_PORT)));
		final String cacheName = env("IGNITE_CACHE", DEFAULT_CACHE);
		final String outEnv = System.getenv("COMPARE_RESULTS_DIR");
		final Path outDir = (outEnv == null || outEnv.isBlank())
				? Path.of("benchmarks", "results")
				: Path.of(outEnv);
		Files.createDirectories(outDir);

		final ClientConfiguration cfg = new ClientConfiguration().setAddresses(host + ":" + port);
		try (IgniteClient client = Ignition.startClient(cfg)) {
			final ClientCache<String, String> cache = client.getOrCreateCache(cacheName);
			final List<Double> putUs = new ArrayList<>(ops);
			final List<Double> getUs = new ArrayList<>(ops);
			for (int i = 0; i < ops; i++) {
				final String key = "ik" + i;
				final String val = "xxxxxxxx";
				final long t0 = System.nanoTime();
				cache.put(key, val);
				putUs.add((System.nanoTime() - t0) / 1_000.0);
				final long t1 = System.nanoTime();
				cache.get(key);
				getUs.add((System.nanoTime() - t1) / 1_000.0);
			}
			Collections.sort(putUs);
			Collections.sort(getUs);
			final Path out = outDir.resolve(stamp + "-compare-ignite.json");
			final String endpoint = host + ":" + port;
			final String json = String.format(Locale.ROOT,
					"{\"system\":\"ignite\",\"mode\":\"thin-put-get\",\"ops\":%d,\"putP50Us\":%.3f,\"putAvgUs\":%.3f,"
							+ "\"getP50Us\":%.3f,\"getAvgUs\":%.3f,\"stamp\":\"%s\",\"client\":\"ignite-thin\",\"endpoint\":\"%s\"}%n",
					ops, percentile(putUs, 0.50), avg(putUs), percentile(getUs, 0.50), avg(getUs), stamp, endpoint);
			Files.writeString(out, json, StandardCharsets.UTF_8);
			System.out.println("Wrote " + out.toAbsolutePath());
		}
	}

	private static double percentile(List<Double> sorted, double p) {
		if (sorted.isEmpty()) {
			return 0d;
		}
		final int idx = Math.min(sorted.size() - 1, Math.max(0, (int) Math.round((sorted.size() - 1) * p)));
		return sorted.get(idx);
	}

	private static double avg(List<Double> values) {
		double sum = 0d;
		for (double v : values) {
			sum += v;
		}
		return values.isEmpty() ? 0d : sum / values.size();
	}

	private static String env(String key, String def) {
		final String v = System.getenv(key);
		return v == null || v.isBlank() ? def : v;
	}

	private static boolean reachable(String host, int port) {
		try (java.net.Socket s = new java.net.Socket()) {
			s.connect(new java.net.InetSocketAddress(host, port), 800);
			return true;
		} catch (Exception e) {
			return false;
		}
	}
}