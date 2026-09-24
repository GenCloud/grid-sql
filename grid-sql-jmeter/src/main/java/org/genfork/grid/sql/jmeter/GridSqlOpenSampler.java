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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket-style Open Connection: one TCP per JMeter thread into {@link GridSqlJmeterSession}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class GridSqlOpenSampler extends AbstractJavaSamplerClient {

	private static final Logger log = LoggerFactory.getLogger(GridSqlOpenSampler.class);

	public static final String PARAM_MODE = "MODE";
	public static final String MODE_OPEN = "OPEN";
	public static final String MODE_SETUP = "SETUP";
	private static final String LABEL_OPEN = "grid-sql-open";
	private static final String LABEL_SETUP = "grid-sql-setup";
	private static final String RESPONSE_OK = "200";
	private static final String RESPONSE_ERR = "500";

	@Override
	public Arguments getDefaultParameters() {
		final Arguments args = new Arguments();
		args.addArgument(GridSqlJmeterSession.PARAM_GRID_URL, GridSqlJmeterSession.DEFAULT_GRID_URL);
		args.addArgument(GridSqlJmeterSession.PARAM_USER, "");
		args.addArgument(GridSqlJmeterSession.PARAM_PASSWORD, "");
		args.addArgument(GridSqlJmeterSession.PARAM_CONNECT_TIMEOUT_MS,
				Integer.toString(GridSqlJmeterSession.DEFAULT_CONNECT_TIMEOUT_MS));
		args.addArgument(GridSqlJmeterSession.PARAM_OP_TIMEOUT_MS,
				Integer.toString(GridSqlJmeterSession.DEFAULT_OP_TIMEOUT_MS));
		args.addArgument(GridSqlJmeterSession.PARAM_KEY_SPACE,
				Integer.toString(GridSqlJmeterSession.DEFAULT_KEY_SPACE));
		args.addArgument(GridSqlJmeterSession.PARAM_SEED_ROWS,
				Integer.toString(GridSqlJmeterSession.DEFAULT_SEED_ROWS));
		args.addArgument(GridSqlJmeterSession.PARAM_MIX_PROFILE,
				GridSqlJmeterSession.MixProfile.CAPACITY.name());
		args.addArgument(GridSqlJmeterSession.PARAM_STRIPE_THREADS, "0");
		args.addArgument(GridSqlJmeterSession.PARAM_WRITE_BATCH_SIZE,
				Integer.toString(GridSqlJmeterSession.DEFAULT_WRITE_BATCH_SIZE));
		args.addArgument(GridSqlLoadSqlTemplates.PROP_TABLE_A, "");
		args.addArgument(GridSqlLoadSqlTemplates.PROP_TABLE_B, "");
		args.addArgument(GridSqlLoadSqlTemplates.PROP_SQL_EQ_LIMIT, "");
		args.addArgument(GridSqlLoadSqlTemplates.PROP_SQL_UPSERT, "");
		args.addArgument(GridSqlLoadSqlTemplates.PROP_SQL_SHORT_TX_SELECT, "");
		args.addArgument(GridSqlLoadSqlTemplates.PROP_SQL_SHORT_TX_UPDATE, "");
		args.addArgument(GridSqlLoadSqlTemplates.PROP_SQL_COUNT_JOIN, "");
		args.addArgument(PARAM_MODE, MODE_OPEN);
		return args;
	}

	@Override
	public SampleResult runTest(JavaSamplerContext context) {
		final SampleResult result = new SampleResult();
		result.sampleStart();
		try {
			final GridSqlJmeterSession.MixProfile mix = GridSqlJmeterSession.MixProfile.parse(
					context.getParameter(GridSqlJmeterSession.PARAM_MIX_PROFILE,
							GridSqlJmeterSession.MixProfile.CAPACITY.name()));
			final GridSqlLoadSqlTemplates sqlTemplates = GridSqlLoadSqlTemplates.resolve(
					GridSqlLoadSqlTemplates.PropSource.samplerThenSystem(context::getParameter));
			final GridSqlJmeterSession session = GridSqlJmeterSession.open(
					context.getParameter(GridSqlJmeterSession.PARAM_GRID_URL),
					context.getParameter(GridSqlJmeterSession.PARAM_USER),
					context.getParameter(GridSqlJmeterSession.PARAM_PASSWORD),
					context.getIntParameter(GridSqlJmeterSession.PARAM_CONNECT_TIMEOUT_MS,
							GridSqlJmeterSession.DEFAULT_CONNECT_TIMEOUT_MS),
					context.getIntParameter(GridSqlJmeterSession.PARAM_OP_TIMEOUT_MS,
							GridSqlJmeterSession.DEFAULT_OP_TIMEOUT_MS),
					context.getIntParameter(GridSqlJmeterSession.PARAM_KEY_SPACE,
							GridSqlJmeterSession.DEFAULT_KEY_SPACE),
					context.getIntParameter(GridSqlJmeterSession.PARAM_SEED_ROWS,
							GridSqlJmeterSession.DEFAULT_SEED_ROWS),
					mix,
					context.getIntParameter(GridSqlJmeterSession.PARAM_STRIPE_THREADS, 0),
					resolveWriteBatchSize(context),
					sqlTemplates);
			GridSqlJmeterSession.bind(session);
			final String mode = context.getParameter(PARAM_MODE, MODE_OPEN);
			if (MODE_SETUP.equalsIgnoreCase(mode == null ? MODE_OPEN : mode.trim())) {
				result.setSampleLabel(LABEL_SETUP);
				session.runSetupBlocking();
			} else {
				result.setSampleLabel(LABEL_OPEN);
			}
			result.setSuccessful(true);
			result.setResponseCode(RESPONSE_OK);
			result.setResponseMessageOK();
		} catch (Throwable ex) {
			result.setSuccessful(false);
			result.setResponseCode(RESPONSE_ERR);
			result.setResponseMessage(ex.getClass().getSimpleName() + ": " + ex.getMessage());
			result.setErrorCount(1);
			log.error("GridSqlOpenSampler failed mode="
					+ context.getParameter(PARAM_MODE, MODE_OPEN) + ": " + ex.getMessage(), ex);
		} finally {
			result.sampleEnd();
		}
		return result;
	}

	/**
	 * Sampler arg first; else {@code -JWRITE_BATCH_SIZE} system property (CLI load-slo).
	 */
	private static int resolveWriteBatchSize(JavaSamplerContext context) {
		final int fromArgs = context.getIntParameter(
				GridSqlJmeterSession.PARAM_WRITE_BATCH_SIZE,
				GridSqlJmeterSession.DEFAULT_WRITE_BATCH_SIZE);
		if (fromArgs > GridSqlJmeterSession.DEFAULT_WRITE_BATCH_SIZE) {
			return fromArgs;
		}
		final String prop = System.getProperty(GridSqlJmeterSession.PARAM_WRITE_BATCH_SIZE, "");
		if (prop == null || prop.isBlank()) {
			return fromArgs;
		}
		try {
			return Integer.parseInt(prop.trim());
		} catch (NumberFormatException ignored) {
			return fromArgs;
		}
	}

	@Override
	public void teardownTest(JavaSamplerContext context) {
		try {
			final GridSqlJmeterSession session = GridSqlJmeterSession.require();
			session.close();
		} catch (Exception ignored) {
			// thread may already have closed
		}
	}
}
