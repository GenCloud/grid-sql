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

import org.genfork.grid.codec.duplex.DuplexCodecSupport;
import org.genfork.grid.codec.duplex.DuplexRepairMode;
import org.genfork.grid.context.config.GridConfigurationProperties.DuplexProps;
import org.genfork.grid.overlay.OverlayStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

/**
 * @author: GenCloud
 * @date: 2026/07
 * @since: 1.0
 */
@Configuration
@Primary
@EnableConfigurationProperties(GridConfigurationProperties.class)
public class GridAutoConfiguration {
	@Bean
	public Boolean duplexConfigurer(GridConfigurationProperties properties) {
		applyDuplex(properties);
		return Boolean.TRUE;
	}

	static void applyDuplex(GridConfigurationProperties properties) {
		final DuplexProps duplex = properties.getCodec().getDuplex();
		DuplexRepairMode mode;
		try {
			mode = DuplexRepairMode.valueOf(duplex.getRepairMode());
		} catch (Exception e) {
			mode = DuplexRepairMode.REBUILD_DATA_FROM_PARITY;
		}
		DuplexCodecSupport.configure(
				duplex.isEnabled(),
				mode,
				duplex.isVerifyOnWrite(),
				duplex.isVerifyOnRead(),
				duplex.getSchemaEpoch(),
				duplex.getApplyTo().isMapValues(),
				duplex.getApplyTo().isReplicationLog()
		);
	}

	@Bean
	public OverlayStore overlayStore(GridConfigurationProperties properties) {
		Path durableDir = null;
		if (properties.getOverlay().isEnabled() && properties.getOverlay().isDurable()) {
			final GridConfigurationProperties.ReplicationProps repl = properties.getReplication();
			durableDir = Path.of(repl.getOpLog().getDataDir())
					.resolve(repl.getClusterId() == null ? "default" : repl.getClusterId())
					.resolve(repl.getNodeId() == null ? "node" : repl.getNodeId())
					.resolve("overlay");
		}
		return new OverlayStore(properties.getOverlay().isEnabled(), durableDir);
	}

	@Bean("gridMessageSource")
	public MessageSource messageSource() {
		final ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
		messageSource.setBasenames("classpath:messages");
		messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
		messageSource.setFallbackToSystemLocale(true);
		return messageSource;
	}

	@Bean("gridMessageSourceAccessor")
	public MessageSourceAccessor messageSourceAccessor(@Qualifier("gridMessageSource") MessageSource messageSource) {
		return new MessageSourceAccessor(messageSource, new Locale("ru"));
	}
}