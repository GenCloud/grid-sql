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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;

/**
 * WebSocket-style Single Write: schedule EXEC and end sample without awaiting response.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class GridSqlWriteSampler extends AbstractJavaSamplerClient {

	private static final String LABEL_WRITE = "grid-sql-write";
	private static final String RESPONSE_OK = "200";
	private static final String RESPONSE_ERR = "500";

	@Override
	public Arguments getDefaultParameters() {
		return new Arguments();
	}

	@Override
	public SampleResult runTest(JavaSamplerContext context) {
		final SampleResult result = new SampleResult();
		result.setSampleLabel(LABEL_WRITE);
		result.sampleStart();
		try {
			final GridSqlJmeterSession session = GridSqlJmeterSession.require();
			final CompletableFuture<Void> future = GridSqlLoadMix.startRandomOp(session);
			session.enqueueWrite(future);
			result.setSuccessful(true);
			result.setResponseCode(RESPONSE_OK);
			result.setResponseMessageOK();
			result.setSamplerData("enqueued");
		} catch (Throwable ex) {
			result.setSuccessful(false);
			result.setResponseCode(RESPONSE_ERR);
			result.setResponseMessage(ex.getClass().getSimpleName() + ": " + ex.getMessage());
			result.setErrorCount(1);
		} finally {
			result.sampleEnd();
		}
		return result;
	}
}