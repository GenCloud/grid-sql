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
package org.genfork.grid.context.config;

import jakarta.annotation.PostConstruct;
import org.genfork.grid.codec.duplex.DuplexCodecSupport;
import org.genfork.grid.codec.duplex.DuplexRepairMode;
import org.genfork.grid.context.config.GridConfigurationProperties.DuplexProps;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Applies {@code grid.codec.duplex} settings to {@link DuplexCodecSupport}.
 *
 * @author: GenCloud
 * @date: 2026/07
 * @since: 1.0
 */
@AutoConfiguration
@EnableConfigurationProperties(GridConfigurationProperties.class)
public class GridDuplexAutoConfiguration {
	private final GridConfigurationProperties properties;

	public GridDuplexAutoConfiguration(GridConfigurationProperties properties) {
		this.properties = properties;
	}

	@PostConstruct
	public void applyDuplexConfig() {
        GridAutoConfiguration.applyDuplex(properties);
	}
}
