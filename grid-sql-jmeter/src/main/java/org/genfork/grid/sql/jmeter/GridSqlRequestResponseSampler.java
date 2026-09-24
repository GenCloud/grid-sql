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
package org.genfork.grid.sql.jmeter;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;

/**
 * Request-response style: start one mixed SQL op and await completion on the same sample.
 * <p>
 * Elapsed time is client e2e from subscribe through await (includes server lock wait /
 * commit), not wire RTT alone. Mix pick / SQL string build are excluded from the clock.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class GridSqlRequestResponseSampler extends AbstractJavaSamplerClient {

	@Override
	public Arguments getDefaultParameters() {
		return new Arguments();
	}

	@Override
	public SampleResult runTest(JavaSamplerContext context) {
		final SampleResult result = new SampleResult();
		CompletableFuture<Void> future = null;
		boolean clockStarted = false;
		try {
			final GridSqlJmeterSession session = GridSqlJmeterSession.require();
			final GridSqlLoadMix.Op op = GridSqlLoadMix.pick(session);
			result.setSampleLabel(op.label());
			result.setSamplerData(op.detail());
			future = op.start(session);
			result.sampleStart();
			clockStarted = true;
			GridSqlJmeterAwait.awaitOp(future, session.opTimeout());
			result.sampleEnd();
			result.setSuccessful(true);
			result.setResponseCode(GridSqlJmeterAwait.RESPONSE_OK);
			result.setResponseMessageOK();
			final int sampleCount = Math.max(1, op.sampleCount());
			result.setSampleCount(sampleCount);
			final byte[] detailBytes = op.detail().getBytes(StandardCharsets.UTF_8);
			result.setSentBytes(detailBytes.length);
			result.setBytes(detailBytes.length);
		} catch (TimeoutException ex) {
			if (!clockStarted) {
				result.sampleStart();
			}
			result.sampleEnd();
			result.setSuccessful(false);
			result.setResponseCode(GridSqlJmeterAwait.RESPONSE_TIMEOUT);
			result.setResponseMessage("TimeoutException: " + ex.getMessage());
			result.setErrorCount(1);
		} catch (Throwable ex) {
			if (!clockStarted) {
				result.sampleStart();
			}
			result.sampleEnd();
			result.setSuccessful(false);
			result.setResponseCode(GridSqlJmeterAwait.responseCodeFor(ex));
			result.setResponseMessage(ex.getClass().getSimpleName() + ": " + ex.getMessage());
			result.setErrorCount(1);
		}
		return result;
	}
}
