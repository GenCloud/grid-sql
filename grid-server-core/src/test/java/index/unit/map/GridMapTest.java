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
package index.unit.map;

import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.serial.SqlWireUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * @author: GenCloud
 * @date: 2025/04
 * @since: 1.0
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class GridMapTest {
	private static final int FILL_COUNT = 1_000_000;

	private GridScalableMap map;
	private List<byte[]> rndValues;

	@BeforeAll
	public void setup() {
		map = new GridScalableMap();

		rndValues = new ArrayList<>(FILL_COUNT);

		for (int key = 0; key < FILL_COUNT; key++) {
			final String value = UUID.randomUUID().toString();
			final byte[] keyArray = SqlWireUtil.toGenericArray(key);
			final byte[] valueArray = value.getBytes(StandardCharsets.UTF_8);

			rndValues.add(valueArray);
			map.put(keyArray, valueArray);
		}
	}

	@Test
	public void _t01_checkAllExists() {
		Assertions.assertEquals(FILL_COUNT, map.size());

		for (int key = 0; key < FILL_COUNT; key++) {
			final byte[] keyArray = SqlWireUtil.toGenericArray(key);
			final byte[] valueArray = map.get(keyArray);
			Assertions.assertNotNull(valueArray);

			Assertions.assertDoesNotThrow(() -> UUID.fromString(new String(valueArray)));
		}
	}

	@Test
	public void _t02_removeAndCheck() {
		for (int key = 0; key < 10; key++) {
			final byte[] keyArray = SqlWireUtil.toGenericArray(key);
			final byte[] valueArray = map.remove(keyArray);
			Assertions.assertNotNull(valueArray);
			Assertions.assertDoesNotThrow(() -> UUID.fromString(new String(valueArray)));
		}
	}

	@Test
	public void _t03_replaceAndCheck() {
		for (int key = 50; key < 70; key++) {
			final byte[] keyArray = SqlWireUtil.toGenericArray(key);
			final byte[] oldValue = map.get(keyArray);
			final byte[] newValue = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
			final byte[] old = map.put(keyArray, newValue);

			Assertions.assertArrayEquals(oldValue, old);
			Assertions.assertArrayEquals(newValue, map.get(keyArray));
		}
	}
}