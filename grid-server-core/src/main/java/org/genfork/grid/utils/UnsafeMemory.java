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
package org.genfork.grid.utils;

import sun.misc.Unsafe;
import java.lang.reflect.Field;

/**
 * Unsafe off-heap memory façade (allocate, free, copy, typed read/write).
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public final class UnsafeMemory {
	public static final long ADDRESS_SIZE;
	public static final long ARRAY_BASE_OFFSET;
	private static final Unsafe unsafe;

	static {
		try {
			final Field field = Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			unsafe = (Unsafe) field.get(null);
		} catch (IllegalArgumentException | SecurityException | NoSuchFieldException | IllegalAccessException e) {
			throw new RuntimeException(e);
		}
		ADDRESS_SIZE = unsafe.addressSize();
		ARRAY_BASE_OFFSET = unsafe.arrayBaseOffset(byte[].class);
	}

	public static long malloc(long size) {
		return unsafe.allocateMemory(size);
	}

	public static long reallocate(long addr, long bytes) {
		return unsafe.reallocateMemory(addr, bytes);
	}

	public static void copy(Object srcBase, long srcOffset, Object destBase, long destOffset, long bytes) {
		unsafe.copyMemory(srcBase, srcOffset, destBase, destOffset, bytes);
	}

	public static void free(long addr) {
		unsafe.freeMemory(addr);
	}

	public static void putAddr(long addr, long val) {
		unsafe.putAddress(addr, val);
	}

	public static long getAddr(long addr) {
		return unsafe.getAddress(addr);
	}

	public static void writeByte(long addr, byte val) {
		unsafe.putByte(addr, val);
	}

	public static void writeInt(long addr, int val) {
		unsafe.putInt(addr, val);
	}

	public static void writeLong(long addr, long val) {
		unsafe.putLong(addr, val);
	}

	public static byte readByte(long addr) {
		return unsafe.getByte(addr);
	}

	public static short readShort(long addr) {
		return unsafe.getShort(addr);
	}

	public static int readInt(long addr) {
		return unsafe.getInt(addr);
	}

	public static float readFloat(long addr) {
		return unsafe.getFloat(addr);
	}

	public static long readLong(long addr) {
		return unsafe.getLong(addr);
	}

	public static double readDouble(long addr) {
		return unsafe.getDouble(addr);
	}

	@SuppressWarnings("deprecation")
	public static long objectFieldOffset(Field field) {
		return unsafe.objectFieldOffset(field);
	}

	public static long arrayBaseOffset(Class<?> arrayClass) {
		return unsafe.arrayBaseOffset(arrayClass);
	}

	public static int arrayIndexScale(Class<?> arrayClass) {
		return unsafe.arrayIndexScale(arrayClass);
	}

	public static Object getObjectVolatile(Object o, long offset) {
		return unsafe.getObjectVolatile(o, offset);
	}

	public static void putObjectVolatile(Object o, long offset, Object v) {
		unsafe.putObjectVolatile(o, offset, v);
	}

	public static boolean compareAndSwapObject(Object o, long offset, Object expected, Object x) {
		return unsafe.compareAndSwapObject(o, offset, expected, x);
	}

	public static boolean compareAndSwapInt(Object o, long offset, int expected, int x) {
		return unsafe.compareAndSwapInt(o, offset, expected, x);
	}

	public static boolean compareAndSwapLong(Object o, long offset, long expected, long x) {
		return unsafe.compareAndSwapLong(o, offset, expected, x);
	}

	public static int getAndAddInt(Object o, long offset, int v) {
		return unsafe.getAndAddInt(o, offset, v);
	}

	private UnsafeMemory() {
		throw new java.lang.UnsupportedOperationException("This is a utility class and cannot be instantiated");
	}

	public static Unsafe getUnsafe() {
		return UnsafeMemory.unsafe;
	}
}
