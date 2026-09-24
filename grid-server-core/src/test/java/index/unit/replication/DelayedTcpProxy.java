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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process TCP forwarder with optional one-way latency (toxiproxy stand-in for unit/JMH).
 */
public final class DelayedTcpProxy implements AutoCloseable {
	private final ServerSocket server;
	private final String targetHost;
	private final int targetPort;
	private final long delayMs;
	private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
		final Thread t = new Thread(r, "delayed-tcp-proxy");
		t.setDaemon(true);
		return t;
	});
	private final AtomicBoolean open = new AtomicBoolean(true);

	public DelayedTcpProxy(String targetHost, int targetPort, long delayMs) throws IOException {
		this.targetHost = targetHost;
		this.targetPort = targetPort;
		this.delayMs = Math.max(0L, delayMs);
		this.server = new ServerSocket();
		this.server.setReuseAddress(true);
		this.server.bind(new InetSocketAddress("127.0.0.1", 0));
		pool.execute(this::acceptLoop);
	}

	public int localPort() {
		return server.getLocalPort();
	}

	private void acceptLoop() {
		while (open.get()) {
			try {
				final Socket client = server.accept();
				pool.execute(() -> handle(client));
			} catch (IOException e) {
				if (open.get()) {
					break;
				}
			}
		}
	}

	private void handle(Socket client) {
		try (client; Socket upstream = new Socket(targetHost, targetPort)) {
			final InputStream clientIn = client.getInputStream();
			final OutputStream clientOut = client.getOutputStream();
			final InputStream upIn = upstream.getInputStream();
			final OutputStream upOut = upstream.getOutputStream();
			pool.execute(() -> pump(clientIn, upOut, delayMs));
			pump(upIn, clientOut, delayMs);
		} catch (IOException ignored) {
			// closed
		}
	}

	private static void pump(InputStream in, OutputStream out, long delayMs) {
		final byte[] buf = new byte[8192];
		try {
			int n;
			while ((n = in.read(buf)) >= 0) {
				if (delayMs > 0) {
					Thread.sleep(delayMs);
				}
				if (n == 0) {
					continue;
				}
				out.write(buf, 0, n);
				out.flush();
			}
		} catch (Exception ignored) {
			// peer closed
		}
	}

	@Override
	public void close() {
		open.set(false);
		try {
			server.close();
		} catch (IOException ignored) {
		}
		pool.shutdownNow();
	}
}