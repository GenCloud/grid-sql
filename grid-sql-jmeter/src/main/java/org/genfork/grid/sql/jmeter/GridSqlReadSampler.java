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

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;

/**
 * WebSocket-style Single Read: await next inflight response from session FIFO.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class GridSqlReadSampler extends AbstractJavaSamplerClient {

	private static final String LABEL_READ = "grid-sql-read";

	@Override
	public Arguments getDefaultParameters() {
		return new Arguments();
	}

	@Override
	public SampleResult runTest(JavaSamplerContext context) {
		final SampleResult result = new SampleResult();
		result.setSampleLabel(LABEL_READ);
		result.sampleStart();
		try {
			GridSqlJmeterSession.require().awaitNextRead();
			result.setSuccessful(true);
			result.setResponseCode(GridSqlJmeterAwait.RESPONSE_OK);
			result.setResponseMessageOK();
		} catch (Throwable ex) {
			result.setSuccessful(false);
			result.setResponseCode(GridSqlJmeterAwait.responseCodeFor(ex));
			result.setResponseMessage(ex.getClass().getSimpleName() + ": " + ex.getMessage());
			result.setErrorCount(1);
		} finally {
			result.sampleEnd();
		}
		return result;
	}
}