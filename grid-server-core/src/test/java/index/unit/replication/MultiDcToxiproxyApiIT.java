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
package index.unit.replication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.crossdc.CrossDcMode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SYNC_VOTERS_ACROSS_DC through a real Toxiproxy listen-proxy (HTTP API :8474).
 * Skips when Toxiproxy is not reachable — DelayedTcpProxy remains the offline stand-in.
 *
 * <pre>
 * docker compose -f benchmarks/compare/docker-compose.yml \
 *   -f benchmarks/compare/docker-compose.toxiproxy.yml --profile wan up -d toxiproxy
 * </pre>
 */
public class MultiDcToxiproxyApiIT {

	private static final String TOXIPROXY_BASE = System.getenv().getOrDefault(
			"TOXIPROXY_URL", "http://127.0.0.1:8474");
	private static final int PROXY_LISTEN_PORT = Integer.parseInt(
			System.getenv().getOrDefault("TOXIPROXY_LISTEN_PORT", "19000"));
	private static final String PROXY_NAME = "jamoa-wan-b";
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(2))
			.build();

	@TempDir
	Path tempDir;

	@Test
	void latencyToxicRaisesP50AndTimeoutFailClosed() throws Exception {
		Assumptions.assumeTrue(toxiproxyReachable(),
				"Toxiproxy API not reachable at " + TOXIPROXY_BASE
						+ " — start compose profile wan or rely on DelayedTcpProxy IT");

		final int portA = freePort();
		final int portB = freePort();
		final String upstreamHost = System.getenv().getOrDefault(
				"TOXIPROXY_UPSTREAM_HOST", "host.docker.internal");

		final GridConfigurationProperties propsB = ReplTestSupport.props(
				"tox-b", "tox-mdc", "dc-b", portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("tox-a", "dc-a", portA))
		);
		propsB.getReplication().getCrossDc().setMode(CrossDcMode.ASYNC_SHIP.name());
		propsB.getReplication().getCrossDc().setPhaseCoupling(false);

		final ReplicationCoordinator b = new ReplicationCoordinator(propsB);
		b.start();

		deleteProxyQuiet(PROXY_NAME);
		createProxy(PROXY_NAME, "0.0.0.0:" + PROXY_LISTEN_PORT, upstreamHost + ":" + portB);
		createToxic(PROXY_NAME, "wan-latency-down", "latency", "downstream", 50);
		createToxic(PROXY_NAME, "wan-latency-up", "latency", "upstream", 50);

		try {
			final GridConfigurationProperties propsA = ReplTestSupport.props(
					"tox-a", "tox-mdc", "dc-a", portA, tempDir.resolve("a"),
					List.of(ReplTestSupport.peer("tox-b", "dc-b", PROXY_LISTEN_PORT))
			);
			propsA.getReplication().getCrossDc().setMode(CrossDcMode.SYNC_VOTERS_ACROSS_DC.name());
			propsA.getReplication().getCrossDc().setVoters(List.of("tox-b"));
			propsA.getReplication().getCrossDc().setPhaseCoupling(false);
			propsA.getReplication().getCrossDc().setRemoteAckTimeoutMs(3_000L);

			final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
			a.start();
			try {
				waitPeers(a, b, 20_000);
				Thread.sleep(300);

				final List<Long> samples = new ArrayList<>();
				for (int i = 1; i <= 5; i++) {
					final long n = i;
					final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
							"demo.Tox", 0, n, ReplicationOpType.UPSERT,
							new byte[]{(byte) n}, new byte[]{1}, 1L, 0L
					));
					final long t0 = System.nanoTime();
					final long seq = a.getOrchidNode().appendAndWaitCommit(op).get(10, TimeUnit.SECONDS);
					samples.add((System.nanoTime() - t0) / 1_000_000L);
					assertTrue(seq >= 1);
					a.getOrchidNode().confirmPersisted(seq);
				}
				samples.sort(Long::compareTo);
				final long p50 = samples.get(samples.size() / 2);
				assertTrue(p50 >= 40L,
						"expected toxiproxy latency to inflate commit p50, got " + p50 + "ms samples=" + samples);

				setProxyEnabled(PROXY_NAME, false);
				final ReplicationOp op2 = OpLogCodec.withChecksum(new ReplicationOp(
						"demo.Tox", 0, 99L, ReplicationOpType.UPSERT, new byte[]{9}, new byte[]{2}, 1L, 0L
				));
				final CompletionException failed = assertThrows(CompletionException.class,
						() -> a.getOrchidNode().appendAndWaitCommit(op2).join());
				Throwable cause = failed.getCause() == null ? failed : failed.getCause();
				assertTrue(cause instanceof OrchidNotSyncedException, "got " + cause);
				assertTrue(cause.getMessage().contains("remote voter digest timeout"), cause.getMessage());
			} finally {
				a.stop();
			}
		} finally {
			try {
				b.stop();
			} catch (Exception ignored) {
			}
			deleteProxyQuiet(PROXY_NAME);
		}
	}

	private static boolean toxiproxyReachable() {
		try {
			final HttpRequest req = HttpRequest.newBuilder(URI.create(TOXIPROXY_BASE + "/version"))
					.timeout(Duration.ofSeconds(2))
					.GET()
					.build();
			final HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
			return resp.statusCode() >= 200 && resp.statusCode() < 500;
		} catch (Exception e) {
			return false;
		}
	}

	private static void createProxy(String name, String listen, String upstream) throws IOException, InterruptedException {
		final ObjectNode body = JSON.createObjectNode();
		body.put("name", name);
		body.put("listen", listen);
		body.put("upstream", upstream);
		body.put("enabled", true);
		final HttpRequest req = HttpRequest.newBuilder(URI.create(TOXIPROXY_BASE + "/proxies"))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();
		final HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
		assertTrue(resp.statusCode() >= 200 && resp.statusCode() < 300,
				"create proxy failed status=" + resp.statusCode() + " body=" + resp.body());
	}

	private static void createToxic(String proxy, String name, String type, String stream, int latencyMs)
			throws IOException, InterruptedException {
		final ObjectNode body = JSON.createObjectNode();
		body.put("name", name);
		body.put("type", type);
		body.put("stream", stream);
		body.put("toxicity", 1.0);
		final ObjectNode attrs = body.putObject("attributes");
		attrs.put("latency", latencyMs);
		attrs.put("jitter", 0);
		final HttpRequest req = HttpRequest.newBuilder(
						URI.create(TOXIPROXY_BASE + "/proxies/" + proxy + "/toxics"))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();
		final HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
		assertTrue(resp.statusCode() >= 200 && resp.statusCode() < 300,
				"create toxic failed status=" + resp.statusCode() + " body=" + resp.body());
	}

	private static void setProxyEnabled(String name, boolean enabled) throws IOException, InterruptedException {
		final ObjectNode body = JSON.createObjectNode();
		body.put("enabled", enabled);
		final HttpRequest req = HttpRequest.newBuilder(URI.create(TOXIPROXY_BASE + "/proxies/" + name))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();
		final HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
		assertTrue(resp.statusCode() >= 200 && resp.statusCode() < 300,
				"set enabled failed status=" + resp.statusCode() + " body=" + resp.body());
	}

	private static void deleteProxyQuiet(String name) {
		try {
			final HttpRequest req = HttpRequest.newBuilder(URI.create(TOXIPROXY_BASE + "/proxies/" + name))
					.timeout(Duration.ofSeconds(3))
					.DELETE()
					.build();
			HTTP.send(req, HttpResponse.BodyHandlers.ofString());
		} catch (Exception ignored) {
		}
	}

	private static void waitPeers(ReplicationCoordinator a, ReplicationCoordinator b, long timeoutMs)
			throws InterruptedException {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (a.getOrchidNode().livePeerCount() >= 1
					&& b.getOrchidNode().livePeerCount() >= 1
					&& a.getOrchidNode().isSynced()) {
				return;
			}
			Thread.sleep(50);
		}
		throw new IllegalStateException("peers not ready via toxiproxy");
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}