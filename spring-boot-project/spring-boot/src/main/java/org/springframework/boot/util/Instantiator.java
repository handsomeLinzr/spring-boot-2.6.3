/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.util;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Simple factory used to instantiate objects by injecting available parameters.
 *
 * @param <T> the type to instantiate
 * @author Phillip Webb
 * @since 2.4.0
 */
public class Instantiator<T> {

	// 优先参数多的在前
	private static final Comparator<Constructor<?>> CONSTRUCTOR_COMPARATOR = Comparator
			.<Constructor<?>>comparingInt(Constructor::getParameterCount).reversed();

	// 用来实例化的类型
	private final Class<?> type;

	// 用来实例化的时候，填充的构造函数的参数可选值
	private final Map<Class<?>, Function<Class<?>, Object>> availableParameters;

	/**
	 * Create a new {@link Instantiator} instance for the given type.
	 * @param type the type to instantiate
	 * @param availableParameters consumer used to register available parameters
	 */
	public Instantiator(Class<?> type, Consumer<AvailableParameters> availableParameters) {
		this.type = type;
		this.availableParameters = getAvailableParameters(availableParameters);
	}

	private Map<Class<?>, Function<Class<?>, Object>> getAvailableParameters(
			Consumer<AvailableParameters> availableParameters) {
		Map<Class<?>, Function<Class<?>, Object>> result = new LinkedHashMap<>();
		availableParameters.accept(new AvailableParameters() {

			@Override
			public void add(Class<?> type, Object instance) {
				// 设置 类型和值的映射
				result.put(type, (factoryType) -> instance);
			}

			@Override
			public void add(Class<?> type, Function<Class<?>, Object> factory) {
				// 设置类型和方法的映射
				result.put(type, factory);
			}

		});
		// 返回收集的结果
		return Collections.unmodifiableMap(result);
	}

	/**
	 * Instantiate the given set of class name, injecting constructor arguments as
	 * necessary.
	 * @param names the class names to instantiate
	 * @return a list of instantiated instances
	 */
	public List<T> instantiate(Collection<String> names) {
		return instantiate((ClassLoader) null, names);
	}

	/**
	 * Instantiate the given set of class name, injecting constructor arguments as
	 * necessary.
	 * @param classLoader the source classloader
	 * @param names the class names to instantiate
	 * @return a list of instantiated instances
	 * @since 2.4.8
	 */
	public List<T> instantiate(ClassLoader classLoader, Collection<String> names) {
		Assert.notNull(names, "Names must not be null");
		return instantiate(names.stream().map((name) -> TypeSupplier.forName(classLoader, name)));
	}

	/**
	 * Instantiate the given set of classes, injecting constructor arguments as necessary.
	 * @param types the types to instantiate
	 * @return a list of instantiated instances
	 * @since 2.4.8
	 */
	public List<T> instantiateTypes(Collection<Class<?>> types) {
		Assert.notNull(types, "Types must not be null");
		return instantiate(types.stream().map((type) -> TypeSupplier.forType(type)));
	}

	private List<T> instantiate(Stream<TypeSupplier> typeSuppliers) {
		List<T> instances = typeSuppliers.map(this::instantiate).collect(Collectors.toList());
		AnnotationAwareOrderComparator.sort(instances);
		return Collections.unmodifiableList(instances);
	}

	private T instantiate(TypeSupplier typeSupplier) {
		try {
			// 获取要实例化的类的类对象
			Class<?> type = typeSupplier.get();
			Assert.isAssignable(this.type, type);
			// 实例化
			return instantiate(type);
		}
		catch (Throwable ex) {
			throw new IllegalArgumentException(
					"Unable to instantiate " + this.type.getName() + " [" + typeSupplier.getName() + "]", ex);
		}
	}

	@SuppressWarnings("unchecked")
	private T instantiate(Class<?> type) throws Exception {
		// 获取所有的构造函数
		Constructor<?>[] constructors = type.getDeclaredConstructors();
		// 排序，按照构造函数，多的在前，少的在后
		Arrays.sort(constructors, CONSTRUCTOR_COMPARATOR);
		// 遍历所有构造函数
		for (Constructor<?> constructor : constructors) {
			// 根据构造函数的参数类型，获取参数
			Object[] args = getArgs(constructor.getParameterTypes());
			if (args != null) {
				ReflectionUtils.makeAccessible(constructor);
				// 实例化
				return (T) constructor.newInstance(args);
			}
		}
		throw new IllegalAccessException("Unable to find suitable constructor");
	}

	/**
	 * 根据参数类型，获取参数
	 * @param parameterTypes
	 * @return
	 */
	private Object[] getArgs(Class<?>[] parameterTypes) {
		// 创建一个数组，用来组装参数
		Object[] args = new Object[parameterTypes.length];
		for (int i = 0; i < parameterTypes.length; i++) {
			// 根据参数类型，获取可用的参数
			Function<Class<?>, Object> parameter = getAvailableParameter(parameterTypes[i]);
			if (parameter == null) {
				return null;
			}
			args[i] = parameter.apply(this.type);
		}
		return args;
	}

	private Function<Class<?>, Object> getAvailableParameter(Class<?> parameterType) {
		for (Map.Entry<Class<?>, Function<Class<?>, Object>> entry : this.availableParameters.entrySet()) {
			if (entry.getKey().isAssignableFrom(parameterType)) {
				return entry.getValue();
			}
		}
		return null;
	}

	/**
	 * Callback used to register available parameters.
	 */
	public interface AvailableParameters {

		/**
		 * Add a parameter with an instance value.
		 * @param type the parameter type
		 * @param instance the instance that should be injected
		 */
		void add(Class<?> type, Object instance);

		/**
		 * Add a parameter with an instance factory.
		 * @param type the parameter type
		 * @param factory the factory used to create the instance that should be injected
		 */
		void add(Class<?> type, Function<Class<?>, Object> factory);

	}

	/**
	 * {@link Supplier} that provides a class type.
	 */
	private interface TypeSupplier {

		String getName();

		Class<?> get() throws ClassNotFoundException;

		static TypeSupplier forName(ClassLoader classLoader, String name) {
			return new TypeSupplier() {

				@Override
				public String getName() {
					return name;
				}

				@Override
				public Class<?> get() throws ClassNotFoundException {
					return ClassUtils.forName(name, classLoader);
				}

			};
		}

		static TypeSupplier forType(Class<?> type) {
			return new TypeSupplier() {

				@Override
				public String getName() {
					return type.getName();
				}

				@Override
				public Class<?> get() throws ClassNotFoundException {
					return type;
				}

			};
		}

	}

}
