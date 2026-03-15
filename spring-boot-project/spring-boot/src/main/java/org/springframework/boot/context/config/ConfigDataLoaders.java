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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.logging.Log;

import org.springframework.boot.BootstrapContext;
import org.springframework.boot.BootstrapRegistry;
import org.springframework.boot.ConfigurableBootstrapContext;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.boot.util.Instantiator;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.log.LogMessage;
import org.springframework.util.Assert;

/**
 * A collection of {@link ConfigDataLoader} instances loaded via {@code spring.factories}.
 *
 * @author Phillip Webb
 * @author Madhura Bhave
 */
class ConfigDataLoaders {

	private final Log logger;

	// ConfigTreeConfigDataLoader
	// StandardConfigDataLoader
	private final List<ConfigDataLoader<?>> loaders;

	// loaders 资源的类型
	// ConfigTreeConfigDataResource
	// StandardConfigDataResource
	private final List<Class<?>> resourceTypes;

	/**
	 * Create a new {@link ConfigDataLoaders} instance.
	 * @param logFactory the deferred log factory
	 * @param bootstrapContext the bootstrap context
	 * @param classLoader the class loader used when loading
	 */
	ConfigDataLoaders(DeferredLogFactory logFactory, ConfigurableBootstrapContext bootstrapContext,
			ClassLoader classLoader) {
		this(logFactory, bootstrapContext, classLoader,
				// ConfigTreeConfigDataLoader
				// StandardConfigDataLoader
				SpringFactoriesLoader.loadFactoryNames(ConfigDataLoader.class, classLoader));
	}

	/**
	 * Create a new {@link ConfigDataLoaders} instance.
	 * @param logFactory the deferred log factory
	 * @param bootstrapContext the bootstrap context
	 * @param classLoader the class loader used when loading
	 * @param names the {@link ConfigDataLoader} class names instantiate
	 */
	ConfigDataLoaders(DeferredLogFactory logFactory, ConfigurableBootstrapContext bootstrapContext,
			ClassLoader classLoader, List<String> names) {
		this.logger = logFactory.getLog(getClass());
		Instantiator<ConfigDataLoader<?>> instantiator = new Instantiator<>(ConfigDataLoader.class,
				(availableParameters) -> {
					// 添加参数，用于构造函数处理
					availableParameters.add(Log.class, logFactory::getLog);
					availableParameters.add(DeferredLogFactory.class, logFactory);
					availableParameters.add(ConfigurableBootstrapContext.class, bootstrapContext);
					availableParameters.add(BootstrapContext.class, bootstrapContext);
					availableParameters.add(BootstrapRegistry.class, bootstrapContext);
				});
		// 设置，也是 SPI 机制
		this.loaders = instantiator.instantiate(classLoader, names);
		// loaders 的资源类型
		this.resourceTypes = getResourceTypes(this.loaders);
	}

	/**
	 * 根据 loaders 获取每个加载器的资源类型
	 * @param loaders
	 * @return
	 */
	private List<Class<?>> getResourceTypes(List<ConfigDataLoader<?>> loaders) {
		List<Class<?>> resourceTypes = new ArrayList<>(loaders.size());
		for (ConfigDataLoader<?> loader : loaders) {
			// 解析得到 loader 对应的泛型
			resourceTypes.add(getResourceType(loader));
		}
		// 返回对应的返回泛型
		return Collections.unmodifiableList(resourceTypes);
	}

    // 获取对应的 resource 具体的类型（泛型）
	private Class<?> getResourceType(ConfigDataLoader<?> loader) {
		//  ResolvableType.forClass(Class).as(泛型.class)   得到对应的泛型
		return ResolvableType.forClass(loader.getClass()).as(ConfigDataLoader.class).resolveGeneric();
	}

	// 加载配置
	/**
	 * Load {@link ConfigData} using the first appropriate {@link ConfigDataLoader}.
	 * @param <R> the resource type
	 * @param context the loader context
	 * @param resource the resource to load
	 * @return the loaded {@link ConfigData}
	 * @throws IOException on IO error
	 */
	<R extends ConfigDataResource> ConfigData load(ConfigDataLoaderContext context, R resource) throws IOException {
		// 根据配置引用，获取对应的配置加载器
		// 默认得到 StandardConfigDataLoader
		ConfigDataLoader<R> loader = getLoader(context, resource);
		this.logger.trace(LogMessage.of(() -> "Loading " + resource + " using loader " + loader.getClass().getName()));
		// 加载 ConfigData，加载配置文件得到结果
		return loader.load(context, resource);
	}

	// 根据资源引用类型，获取加载器
	@SuppressWarnings("unchecked")
	private <R extends ConfigDataResource> ConfigDataLoader<R> getLoader(ConfigDataLoaderContext context, R resource) {
		ConfigDataLoader<R> result = null;
		// 遍历加载器
		for (int i = 0; i < this.loaders.size(); i++) {
			ConfigDataLoader<?> candidate = this.loaders.get(i);
			// 判断该加载器的加载资源类型，是否是 resource 对应的类型
			if (this.resourceTypes.get(i).isInstance(resource)) {
				// 强转加载器
				ConfigDataLoader<R> loader = (ConfigDataLoader<R>) candidate;
				// 默认 true
				if (loader.isLoadable(context, resource)) {
					if (result != null) {
						throw new IllegalStateException("Multiple loaders found for resource '" + resource + "' ["
								+ candidate.getClass().getName() + "," + result.getClass().getName() + "]");
					}
					// 返回对应的加载器
					result = loader;
				}
			}
		}
		Assert.state(result != null, () -> "No loader found for resource '" + resource + "'");
		return result;
	}

}
