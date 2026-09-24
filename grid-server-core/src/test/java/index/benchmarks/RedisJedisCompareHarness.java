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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Host Jedis SET + replica lag probe (ports 6379/6380).
 * Scripts: {@code mvn -pl grid-server-core -Pjmh -Dtest=RedisJedisCompareHarness test}
 * (profile {@code jmh} compiles {@code index/benchmarks}; include {@code *Harness}).
 */
public class RedisJedisCompareHarness {

	@Test
	void compareRedisViaHostJedis() throws Exception {
		Assumptions.assumeTrue(reachable("127.0.0.1", Integer.parseInt(env("REDIS_PORT", "6379"))),
				"Redis primary :6379 not up — start compare compose redis");
		runAndWrite();
	}

	public static void main(String[] args) throws Exception {
		runAndWrite();
	}

	static void runAndWrite() throws Exception {
		final int ops = Integer.parseInt(env("COMPARE_OPS", "200"));
		final String stamp = env("JMH_STAMP", java.time.LocalDate.now().toString());
		final String primaryHost = env("REDIS_HOST", "127.0.0.1");
		final int primaryPort = Integer.parseInt(env("REDIS_PORT", "6379"));
		final String replicaHost = env("REDIS_REPLICA_HOST", primaryHost);
		final int replicaPort = Integer.parseInt(env("REDIS_REPLICA_PORT", "6380"));
		final String outEnv = System.getenv("COMPARE_RESULTS_DIR");
		final Path outDir = (outEnv == null || outEnv.isBlank())
				? Path.of("benchmarks", "results")
				: Path.of(outEnv);
		Files.createDirectories(outDir);

		final JedisPoolConfig cfg = new JedisPoolConfig();
		cfg.setMaxTotal(8);
		try (JedisPool primaryPool = new JedisPool(cfg, primaryHost, primaryPort);
		     JedisPool replicaPool = new JedisPool(cfg, replicaHost, replicaPort);
		     Jedis primary = primaryPool.getResource();
		     Jedis replica = replicaPool.getResource()) {
			primary.ping();
			replica.ping();

			final byte[] payload = "xxxxxxxx".getBytes(StandardCharsets.UTF_8);
			final List<Double> setUs = new ArrayList<>(ops);
			for (int i = 0; i < ops; i++) {
				final String key = "rk" + i;
				final long t0 = System.nanoTime();
				primary.set(key.getBytes(StandardCharsets.UTF_8), payload);
				setUs.add((System.nanoTime() - t0) / 1_000.0);
			}
			Collections.sort(setUs);
			final double p50 = percentile(setUs, 0.50);
			final double avg = setUs.stream().mapToDouble(Double::doubleValue).average().orElse(0);

			final int lagOps = Math.min(50, ops);
			final List<Double> lagUs = new ArrayList<>(lagOps);
			for (int i = 0; i < lagOps; i++) {
				final String key = "lag" + i;
				final String val = "v" + i;
				primary.set(key, val);
				final long t0 = System.nanoTime();
				boolean seen = false;
				while ((System.nanoTime() - t0) / 1_000_000L < 2_000L) {
					final String got = replica.get(key);
					if (val.equals(got)) {
						seen = true;
						break;
					}
					Thread.sleep(1L);
				}
				if (seen) {
					lagUs.add((System.nanoTime() - t0) / 1_000.0);
				}
			}
			Collections.sort(lagUs);
			final double lagP50 = lagUs.isEmpty() ? -1.0 : percentile(lagUs, 0.50);

			final String json = String.format(Locale.ROOT,
					"{\"system\":\"redis\",\"mode\":\"standalone-set+replica-lag\",\"ops\":%d,"
							+ "\"avgUs\":%.3f,\"p50Us\":%.3f,\"replicaLagP50Us\":%.3f,"
							+ "\"stamp\":\"%s\",\"client\":\"host-jedis\","
							+ "\"fairness\":\"host Jedis vs prior docker-cli\"}%n",
					ops, avg, p50, lagP50, stamp);
			final Path out = outDir.resolve(stamp + "-compare-redis.json");
			Files.writeString(out, json, StandardCharsets.UTF_8);
			System.out.print(json);
			System.out.println("Wrote " + out.toAbsolutePath());
		}
	}

	private static boolean reachable(String host, int port) {
		try (java.net.Socket s = new java.net.Socket()) {
			s.connect(new java.net.InetSocketAddress(host, port), 500);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private static double percentile(List<Double> sorted, double p) {
		if (sorted.isEmpty()) {
			return 0;
		}
		final int idx = (int) Math.floor((sorted.size() - 1) * p);
		return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
	}

	private static String env(String key, String def) {
		final String v = System.getenv(key);
		return v == null || v.isBlank() ? def : v;
	}
}