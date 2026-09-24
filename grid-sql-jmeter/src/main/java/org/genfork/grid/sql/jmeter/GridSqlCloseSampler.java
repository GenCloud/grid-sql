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
 * WebSocket-style Close: dispose per-thread {@link GridSqlJmeterSession}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class GridSqlCloseSampler extends AbstractJavaSamplerClient {

	private static final String LABEL_CLOSE = "grid-sql-close";
	private static final String RESPONSE_OK = "200";
	private static final String RESPONSE_ERR = "500";

	@Override
	public Arguments getDefaultParameters() {
		return new Arguments();
	}

	@Override
	public SampleResult runTest(JavaSamplerContext context) {
		final SampleResult result = new SampleResult();
		result.setSampleLabel(LABEL_CLOSE);
		result.sampleStart();
		try {
			final GridSqlJmeterSession session = GridSqlJmeterSession.require();
			session.close();
			result.setSuccessful(true);
			result.setResponseCode(RESPONSE_OK);
			result.setResponseMessageOK();
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