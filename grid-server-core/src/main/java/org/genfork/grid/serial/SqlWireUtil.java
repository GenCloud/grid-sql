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
package org.genfork.grid.serial;

import org.genfork.grid.catalog.SqlTypeCoercion;
import org.genfork.grid.codec.duplex.DuplexBlob;
import org.genfork.grid.codec.duplex.DuplexCodecSupport;
import org.genfork.grid.codec.duplex.QuartetDuplexCodec;
import org.genfork.grid.serial.stream.WireByteSink;
import org.genfork.grid.utils.ClassUtil;
import org.genfork.grid.utils.SerialUtil;
import java.util.UUID;

/**
 * Scalar / wire helpers for SQL keys and duplex unwrap (no domain POJO encode).
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public final class SqlWireUtil {
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SqlWireUtil.class);
	private static final byte[] NULL_PTR = new byte[] {0};

	public static byte[] getNullPtr() {
		return NULL_PTR;
	}

	/**
	 * Encode a SQL scalar (primitive/wrapper, String, array, temporal) to index/PK bytes.
	 */
	public static byte[] toGenericArray(Object instance) {
		if (instance == null) {
			return NULL_PTR;
		}
		final Class<?> type = instance.getClass();
		if (type == UUID.class) {
			return SqlTypeCoercion.uuidToBytes((UUID) instance);
		}
		if (ClassUtil.isPrimitiveOrWrapper(type) || type.isArray()) {
			byte[] array = null;
			final int offset = SerialUtil.getFieldOffset(type, instance);
			try (WireByteSink os = new WireByteSink(offset)) {
				SerialUtil.writePrimitiveData(os, type, instance);
				array = os.toByteArray();
			} catch (Exception e) {
				log.error("Cant serialize input byte array.", e);
			}
			return array;
		}
		if (ClassUtil.isTemporal(type)) {
			byte[] array = null;
			final int offset = SerialUtil.getFieldOffset(type, instance);
			try (WireByteSink os = new WireByteSink(offset)) {
				SerialUtil.writeTemporal(os, type, instance);
				array = os.toByteArray();
			} catch (Exception e) {
				log.error("Cant serialize input byte array.", e);
			}
			return array;
		}
		throw new IllegalArgumentException("Unsupported key/index value type (SQL scalars only): " + type.getName());
	}

	/**
	 * Unwrap duplex wire to logical layout bytes (identity if not duplex).
	 */
	public static byte[] toLogicalBytes(byte[] stored) {
		if (stored == null || stored.length == 0) {
			return stored;
		}
		if (!DuplexBlob.isWire(stored)) {
			return stored;
		}
		final QuartetDuplexCodec codec = DuplexCodecSupport.getCodec();
		final DuplexBlob blob = DuplexBlob.fromWireBytes(stored);
		if (codec.getSchemaEpoch() != 0 && blob.schemaEpoch() != codec.getSchemaEpoch()) {
			throw new IllegalStateException("Duplex schemaEpoch mismatch: blob=" + blob.schemaEpoch() + " local=" + codec.getSchemaEpoch());
		}
		return codec.decodeToLogical(blob);
	}

	private SqlWireUtil() {
		throw new java.lang.UnsupportedOperationException("This is a utility class and cannot be instantiated");
	}
}
