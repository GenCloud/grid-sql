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
package org.genfork.grid.sql.udf;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Resolves {@code CREATE FUNCTION … AS CLASS '…' METHOD '…'} to a {@link SqlUdf}.
 * <p>
 * Rejects unsafe binds (scripting, reflective process/runtime entry points, non-public
 * types). No string eval. Classes implementing {@link SqlMutatingUdf} are bound as
 * mutating scalar UDFs ({@code FunctionDef.mutating=true}).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlUdfBinder {
	private static final Pattern JAVA_NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*$");
	private static final Pattern METHOD_NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

	private SqlUdfBinder() {
	}

	/**
	 * Bind class + method to a callable UDF instance.
	 *
	 * @param className  fully-qualified class name
	 * @param methodName public method name ({@code apply} for {@link SqlUdf} implementors;
	 *                   otherwise a public static {@code Object method(Object[])} )
	 */
	public static SqlUdf bind(String className, String methodName) {
		final String cn = Objects.requireNonNull(className, "className").trim();
		final String mn = Objects.requireNonNull(methodName, "methodName").trim();
		rejectUnsafeClassName(cn);
		if (!METHOD_NAME.matcher(mn).matches()) {
			throw new IllegalArgumentException("unsafe or invalid METHOD name: " + methodName);
		}
		final Class<?> type;
		try {
			type = Class.forName(cn, false, SqlUdfBinder.class.getClassLoader());
		} catch (ClassNotFoundException e) {
			throw new IllegalArgumentException("UDF class not found: " + cn, e);
		}
		rejectUnsafeClass(type);
		if (SqlUdf.class.isAssignableFrom(type)) {
			if (!"apply".equals(mn)) {
				throw new IllegalArgumentException(
						"SqlUdf bind requires METHOD 'apply', got: " + mn);
			}
			return instantiateSqlUdf(type);
		}
		return bindStaticObjectArray(type, mn);
	}

	private static SqlUdf instantiateSqlUdf(Class<?> type) {
		try {
			final Constructor<?> ctor = type.getDeclaredConstructor();
			if (!Modifier.isPublic(ctor.getModifiers()) || !Modifier.isPublic(type.getModifiers())) {
				throw new IllegalArgumentException("UDF class/ctor must be public: " + type.getName());
			}
			final Object instance = ctor.newInstance();
			return (SqlUdf) instance;
		} catch (ReflectiveOperationException e) {
			throw new IllegalArgumentException("failed to instantiate SqlUdf: " + type.getName(), e);
		}
	}

	private static SqlUdf bindStaticObjectArray(Class<?> type, String methodName) {
		final Method method;
		try {
			method = type.getMethod(methodName, Object[].class);
		} catch (NoSuchMethodException e) {
			throw new IllegalArgumentException(
					"UDF METHOD must be public static Object " + methodName + "(Object[]): " + type.getName(), e);
		}
		if (!Modifier.isStatic(method.getModifiers()) || !Modifier.isPublic(method.getModifiers())) {
			throw new IllegalArgumentException("UDF METHOD must be public static: " + methodName);
		}
		if (method.getReturnType() == void.class) {
			throw new IllegalArgumentException("UDF METHOD must return a value: " + methodName);
		}
		return args -> {
			try {
				return method.invoke(null, (Object) args);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("UDF invoke failed: " + type.getName() + "." + methodName, e);
			}
		};
	}

	private static void rejectUnsafeClassName(String className) {
		if (!JAVA_NAME.matcher(className).matches()) {
			throw new IllegalArgumentException("unsafe CLASS name: " + className);
		}
		final String lower = className.toLowerCase(Locale.ROOT);
		if (lower.startsWith("javax.script")
				|| lower.startsWith("jdk.nashorn")
				|| lower.startsWith("org.codehaus.groovy")
				|| lower.startsWith("groovy.")
				|| lower.equals("java.lang.runtime")
				|| lower.startsWith("java.lang.runtime$")
				|| lower.equals("java.lang.processbuilder")
				|| lower.startsWith("java.lang.process")
				|| lower.startsWith("java.lang.reflect")
				|| lower.startsWith("jdk.internal")
				|| lower.startsWith("sun.")
				|| lower.startsWith("com.sun.jndi")
				|| lower.contains("scriptengine")) {
			throw new IllegalArgumentException("unsafe CLASS bind rejected: " + className);
		}
	}

	private static void rejectUnsafeClass(Class<?> type) {
		if (!Modifier.isPublic(type.getModifiers())) {
			throw new IllegalArgumentException("UDF class must be public: " + type.getName());
		}
		if (type.isInterface() && type != SqlUdf.class && type != SqlTableUdf.class
				&& type != SqlMutatingUdf.class) {
			throw new IllegalArgumentException("UDF CLASS must be a concrete type: " + type.getName());
		}
		rejectUnsafeClassName(type.getName());
	}

	/**
	 * Bind class + method to a callable {@link SqlTableUdf}.
	 */
	public static SqlTableUdf bindTable(String className, String methodName) {
		final String cn = Objects.requireNonNull(className, "className").trim();
		final String mn = Objects.requireNonNull(methodName, "methodName").trim();
		rejectUnsafeClassName(cn);
		if (!METHOD_NAME.matcher(mn).matches()) {
			throw new IllegalArgumentException("unsafe or invalid METHOD name: " + methodName);
		}
		final Class<?> type;
		try {
			type = Class.forName(cn, false, SqlUdfBinder.class.getClassLoader());
		} catch (ClassNotFoundException e) {
			throw new IllegalArgumentException("UDF class not found: " + cn, e);
		}
		rejectUnsafeClass(type);
		if (SqlTableUdf.class.isAssignableFrom(type)) {
			if (!"apply".equals(mn)) {
				throw new IllegalArgumentException(
						"SqlTableUdf bind requires METHOD 'apply', got: " + mn);
			}
			return instantiateTableUdf(type);
		}
		return bindStaticObjectArrayAsTable(type, mn);
	}

	private static SqlTableUdf instantiateTableUdf(Class<?> type) {
		try {
			final Constructor<?> ctor = type.getDeclaredConstructor();
			if (!Modifier.isPublic(ctor.getModifiers()) || !Modifier.isPublic(type.getModifiers())) {
				throw new IllegalArgumentException("UDF class/ctor must be public: " + type.getName());
			}
			final Object instance = ctor.newInstance();
			return (SqlTableUdf) instance;
		} catch (ReflectiveOperationException e) {
			throw new IllegalArgumentException("failed to instantiate SqlTableUdf: " + type.getName(), e);
		}
	}

	@SuppressWarnings("unchecked")
	private static SqlTableUdf bindStaticObjectArrayAsTable(Class<?> type, String methodName) {
		final Method method;
		try {
			method = type.getMethod(methodName, Object[].class);
		} catch (NoSuchMethodException e) {
			throw new IllegalArgumentException(
					"UDF METHOD must be public static List/Iterable apply(Object[]): " + type.getName(), e);
		}
		if (!Modifier.isStatic(method.getModifiers()) || !Modifier.isPublic(method.getModifiers())) {
			throw new IllegalArgumentException("UDF METHOD must be public static: " + methodName);
		}
		if (method.getReturnType() == void.class) {
			throw new IllegalArgumentException("UDF METHOD must return a value: " + methodName);
		}
		return args -> {
			try {
				final Object result = method.invoke(null, (Object) args);
				if (result == null) {
					return List.of();
				}
				if (result instanceof List<?> list) {
					final List<byte[]> out = new ArrayList<>(list.size());
					for (Object row : list) {
						if (!(row instanceof byte[] bytes)) {
							throw new IllegalStateException("table UDF must return List<byte[]>");
						}
						out.add(bytes);
					}
					return out;
				}
				if (result instanceof Iterable<?> it) {
					final List<byte[]> out = new ArrayList<>();
					for (Object row : it) {
						if (!(row instanceof byte[] bytes)) {
							throw new IllegalStateException("table UDF must return Iterable<byte[]>");
						}
						out.add(bytes);
					}
					return out;
				}
				throw new IllegalStateException("table UDF must return List/Iterable of byte[]");
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("UDF invoke failed: " + type.getName() + "." + methodName, e);
			}
		};
	}
}
