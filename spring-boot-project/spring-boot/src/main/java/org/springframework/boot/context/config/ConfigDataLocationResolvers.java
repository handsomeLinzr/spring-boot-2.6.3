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

package org.springframework.boot.context.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;

import org.springframework.boot.BootstrapContext;
import org.springframework.boot.BootstrapRegistry;
import org.springframework.boot.ConfigurableBootstrapContext;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.boot.util.Instantiator;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;

/**
 * A collection of {@link ConfigDataLocationResolver} instances loaded via
 * {@code spring.factories}.
 *
 * @author Phillip Webb
 * @author Madhura Bhave
 */
class ConfigDataLocationResolvers {

	// ConfigTreeConfigDataLocationResolver
	// StandardConfigDataLocationResolver   重点
	private final List<ConfigDataLocationResolver<?>> resolvers;

	/**
	 * Create a new {@link ConfigDataLocationResolvers} instance.
	 * @param logFactory a {@link DeferredLogFactory} used to inject {@link Log} instances
	 * @param bootstrapContext the bootstrap context
	 * @param binder a binder providing values from the initial {@link Environment}
	 * @param resourceLoader {@link ResourceLoader} to load resource locations
	 */
	ConfigDataLocationResolvers(DeferredLogFactory logFactory, ConfigurableBootstrapContext bootstrapContext,
			Binder binder, ResourceLoader resourceLoader) {
		this(logFactory, bootstrapContext, binder, resourceLoader, SpringFactoriesLoader
				// 加载 ConfigDataLocationResolver 对应的名称
				// ConfigTreeConfigDataLocationResolver
				// StandardConfigDataLocationResolver
				.loadFactoryNames(ConfigDataLocationResolver.class, resourceLoader.getClassLoader()));
	}

	// 创建一个配置文件解析器
	/**
	 * Create a new {@link ConfigDataLocationResolvers} instance.
	 * @param logFactory a {@link DeferredLogFactory} used to inject {@link Log} instances
	 * @param bootstrapContext the bootstrap context
	 * @param binder {@link Binder} providing values from the initial {@link Environment}
	 * @param resourceLoader {@link ResourceLoader} to load resource locations
	 * @param names the {@link ConfigDataLocationResolver} class names
	 */
	ConfigDataLocationResolvers(DeferredLogFactory logFactory, ConfigurableBootstrapContext bootstrapContext,
			Binder binder, ResourceLoader resourceLoader, List<String> names) {
		Instantiator<ConfigDataLocationResolver<?>> instantiator = new Instantiator<>(ConfigDataLocationResolver.class,
				// 提前添加构造函数的参数值配置
				(availableParameters) -> {
					availableParameters.add(Log.class, logFactory::getLog);
					availableParameters.add(DeferredLogFactory.class, logFactory);
					availableParameters.add(Binder.class, binder);
					availableParameters.add(ResourceLoader.class, resourceLoader);
					availableParameters.add(ConfigurableBootstrapContext.class, bootstrapContext);
					availableParameters.add(BootstrapContext.class, bootstrapContext);
					availableParameters.add(BootstrapRegistry.class, bootstrapContext);
				});
		// instantiate 实例化
		this.resolvers = reorder(instantiator.instantiate(resourceLoader.getClassLoader(), names));
	}

	// 返回对应的实例化的列表
	private List<ConfigDataLocationResolver<?>> reorder(List<ConfigDataLocationResolver<?>> resolvers) {
		// 创建集合
		List<ConfigDataLocationResolver<?>> reordered = new ArrayList<>(resolvers.size());
		StandardConfigDataLocationResolver resourceResolver = null;
		// 遍历
		for (ConfigDataLocationResolver<?> resolver : resolvers) {
			if (resolver instanceof StandardConfigDataLocationResolver) {
				resourceResolver = (StandardConfigDataLocationResolver) resolver;
			}
			else {
				reordered.add(resolver);
			}
		}
		if (resourceResolver != null) {
			reordered.add(resourceResolver);
		}
		// 直接返回得到的 reordered
		return Collections.unmodifiableList(reordered);
	}

	/**
	 * 解析得到配置文件
	 */
	List<ConfigDataResolutionResult> resolve(ConfigDataLocationResolverContext context, ConfigDataLocation location,
			Profiles profiles) {
		if (location == null) {
			return Collections.emptyList();
		}
		// 遍历所有的解析器，默认有  StandardConfigDataLocationResolver
		for (ConfigDataLocationResolver<?> resolver : getResolvers()) {
			// 判断是否符合解析器要求的规则，StandardConfigDataLocationResolver 直接返回 true
			if (resolver.isResolvable(context, location)) {
				// 用解析器进行解析
				return resolve(resolver, context, location, profiles);
			}
		}
		throw new UnsupportedConfigDataLocationException(location);
	}

	/**
	 * 根据给定的解析器，对配置文件路径进行解析
	 */
	private List<ConfigDataResolutionResult> resolve(ConfigDataLocationResolver<?> resolver,
			ConfigDataLocationResolverContext context, ConfigDataLocation location, Profiles profiles) {
		// 先根据配置文件路径，直接进行解析
		List<ConfigDataResolutionResult> resolved = resolve(location, false, () -> resolver.resolve(context, location));  // 走到这里，返回存在的配置文件
		if (profiles == null) {
			// 如果没有 profiles 标识，则直接返回即可
			return resolved;
		}

		// 如果有指定了 profiles，再次获取带 profiles 标识的配置文件
		List<ConfigDataResolutionResult> profileSpecific = resolve(location, true,
				() -> resolver.resolveProfileSpecific(context, location, profiles));
		// 将带标识和不带标识的进行合并成一个集合，然后返回
		return merge(resolved, profileSpecific);
	}

	/**
	 * 根据给定的资源路径，和给定的解析方法，解析出具体配置文件资源结果
	 * 并封装成 ConfigDataResolutionResult 添加进 resolved 中
	 */
	private List<ConfigDataResolutionResult> resolve(ConfigDataLocation location, boolean profileSpecific,
			Supplier<List<? extends ConfigDataResource>> resolveAction) {
		// 调用 resolveAction 方法，返回得到的配置文件 resource 列表
		// 重点就是在 resolveAction.get() 中
		List<ConfigDataResource> resources = nonNullList(resolveAction.get());
		List<ConfigDataResolutionResult> resolved = new ArrayList<>(resources.size());
		// 遍历所有的 resources，封装成 ConfigDataResolutionResult 给到 resolved 中，并返回
		for (ConfigDataResource resource : resources) {
			resolved.add(new ConfigDataResolutionResult(location, resource, profileSpecific));
		}
		return resolved;
	}

	@SuppressWarnings("unchecked")
	private <T> List<T> nonNullList(List<? extends T> list) {
		return (list != null) ? (List<T>) list : Collections.emptyList();
	}

	private <T> List<T> merge(List<T> list1, List<T> list2) {
		List<T> merged = new ArrayList<>(list1.size() + list2.size());
		merged.addAll(list1);
		merged.addAll(list2);
		return merged;
	}

	/**
	 * Return the resolvers managed by this object.
	 * @return the resolvers
	 */
	List<ConfigDataLocationResolver<?>> getResolvers() {
		return this.resolvers;
	}

}
