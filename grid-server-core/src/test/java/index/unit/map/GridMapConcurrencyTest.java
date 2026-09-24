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

import com.google.code.tempusfugit.concurrency.ConcurrentRule;
import com.google.code.tempusfugit.concurrency.RepeatingRule;
import com.google.code.tempusfugit.concurrency.annotations.Concurrent;
import com.google.code.tempusfugit.concurrency.annotations.Repeating;
import org.genfork.grid.mem.GridScalableMap;
import org.junit.Rule;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author: GenCloud
 * @date: 2025/04
 * @since: 1.0
 */
public class GridMapConcurrencyTest {
	@Rule
	public ConcurrentRule concurrentRule = new ConcurrentRule();

	@Rule
	public RepeatingRule repeatingRule = new RepeatingRule();

	private final GridScalableMap map = new GridScalableMap();
	private final AtomicInteger keyCounter = new AtomicInteger(0);

	@Test
	@Concurrent(count = 10)
	@Repeating(repetition = 100)
	public void testConcurrentPut() {
		int keyVal = keyCounter.getAndIncrement();
		byte[] key = ByteBuffer.allocate(4).putInt(keyVal).array();
		byte[] value = ByteBuffer.allocate(4).putInt(keyVal * 2).array();

		assertNull(map.put(key, value));
		assertArrayEquals(value, map.get(key));
	}

	// Тест на конкурентные чтения и записи
	@Test
	@Concurrent(count = 5)
	@Repeating(repetition = 50)
	public void testConcurrentPutGet() {
		byte[] key = ByteBuffer.allocate(4).putInt(ThreadLocalRandom.current().nextInt(100)).array();
		byte[] value = ByteBuffer.allocate(8).putLong(System.currentTimeMillis()).array();

		map.put(key, value);
		byte[] result = map.get(key);

		assertTrue(result == null || Arrays.equals(value, result));
	}

	// Тест на конкурентные удаления
	@Test
	@Concurrent(count = 8)
	@Repeating(repetition = 80)
	public void testConcurrentRemove() {
		byte[] key = ByteBuffer.allocate(4).putInt(ThreadLocalRandom.current().nextInt(50)).array();

		map.remove(key);
		assertFalse(map.containsKey(key));
	}

	// Тест на конкурентную проверку существования ключей
	@Test
	@Concurrent(count = 4)
	@Repeating(repetition = 40)
	public void testContainsKey() {
		byte[] key = ByteBuffer.allocate(4).putInt(ThreadLocalRandom.current().nextInt(20)).array();

		boolean existsBefore = map.containsKey(key);
		map.put(key, new byte[4]);
		boolean existsAfter = map.containsKey(key);

		assertTrue(!existsBefore || existsAfter);
	}
}
