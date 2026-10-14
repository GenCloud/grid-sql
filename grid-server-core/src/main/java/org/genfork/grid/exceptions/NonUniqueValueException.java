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
package org.genfork.grid.exceptions;

/**
 * STRICT / UNIQUE index collision.
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public class NonUniqueValueException extends RuntimeException {
	private static final String MSG_PREFIX_DOMAIN = "domain=";
	private static final String MSG_PREFIX_INDEX = " index=";
	private static final String MSG_PREFIX_VALUE = " value=[";
	private static final String MSG_SUFFIX = "] not unique for strict index.";
	/** Domain placeholder when the BPTree layer has no catalog key. */
	public static final String DOMAIN_UNKNOWN = "?";

	public NonUniqueValueException(String msg) {
		super(msg);
	}

	public NonUniqueValueException(String msg, Throwable cause) {
		super(msg, cause);
	}

	/**
	 * Canonical STRICT collision message ({@code domain=} + {@code index=} + value).
	 */
	public static String formatMessage(String domain, String indexName, Object key) {
		final String domainLabel = domain == null || domain.isEmpty() ? DOMAIN_UNKNOWN : domain;
		final String indexLabel = indexName == null || indexName.isEmpty() ? "?" : indexName;
		return MSG_PREFIX_DOMAIN + domainLabel
				+ MSG_PREFIX_INDEX + indexLabel
				+ MSG_PREFIX_VALUE + key
				+ MSG_SUFFIX;
	}
}
