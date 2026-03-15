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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;

import org.springframework.boot.logging.DeferredLogFactory;

/**
 * Imports {@link ConfigData} by {@link ConfigDataLocationResolver resolving} and
 * {@link ConfigDataLoader loading} locations. {@link ConfigDataResource resources} are
 * tracked to ensure that they are not imported multiple times.
 *
 * @author Phillip Webb
 * @author Madhura Bhave
 */
class ConfigDataImporter {

	private final Log logger;

	private final ConfigDataLocationResolvers resolvers;

	private final ConfigDataLoaders loaders;

	private final ConfigDataNotFoundAction notFoundAction;
    // 解析得到的 ConfigDataResource 配置
	private final Set<ConfigDataResource> loaded = new HashSet<>();

	private final Set<ConfigDataLocation> loadedLocations = new HashSet<>();

	private final Set<ConfigDataLocation> optionalLocations = new HashSet<>();

	/**
	 * Create a new {@link ConfigDataImporter} instance.
	 * @param logFactory the log factory
	 * @param notFoundAction the action to take when a location cannot be found
	 * @param resolvers the config data location resolvers
	 * @param loaders the config data loaders
	 */
	ConfigDataImporter(DeferredLogFactory logFactory, ConfigDataNotFoundAction notFoundAction,
			ConfigDataLocationResolvers resolvers, ConfigDataLoaders loaders) {
		this.logger = logFactory.getLog(getClass());
		this.resolvers = resolvers;
		this.loaders = loaders;
		this.notFoundAction = notFoundAction;
	}

	// 加载并解析配置文件，重点
	/**
	 * Resolve and load the given list of locations, filtering any that have been
	 * previously loaded.
	 * @param activationContext the activation context
	 * @param locationResolverContext the location resolver context
	 * @param loaderContext the loader context
	 * @param locations the locations to resolve
	 * @return a map of the loaded locations and data
	 */
	Map<ConfigDataResolutionResult, ConfigData> resolveAndLoad(ConfigDataActivationContext activationContext,
			ConfigDataLocationResolverContext locationResolverContext, ConfigDataLoaderContext loaderContext,
			List<ConfigDataLocation> locations) {
		try {
			// 先获取配置文件标识信息，没有则 null
			Profiles profiles = (activationContext != null) ? activationContext.getProfiles() : null;
			// 获取得到对应的配置文件
			List<ConfigDataResolutionResult> resolved = resolve(locationResolverContext, profiles, locations);
			// 将上一部得到的所有配置文件引用，都进行解析
			return load(loaderContext, resolved);
		}
		catch (IOException ex) {
			throw new IllegalStateException("IO error on loading imports from " + locations, ex);
		}
	}

	/**
	 * 解析获取配置文件
	 */
	private List<ConfigDataResolutionResult> resolve(ConfigDataLocationResolverContext locationResolverContext,
			Profiles profiles, List<ConfigDataLocation> locations) {
		// 需要进行解析的路径，这个路径本身就是多个路径用分号分隔开的，所以后边还需要再分隔一次做遍历
		List<ConfigDataResolutionResult> resolved = new ArrayList<>(locations.size());
		// 这里是配置文件路径，用分号连接
		for (ConfigDataLocation location : locations) {
			// resolve 解析配置文件路径
			// 添加到 resolved 中
			resolved.addAll(resolve(locationResolverContext, profiles, location));
		}
		return Collections.unmodifiableList(resolved);
	}

	/**
	 * 解析获取配置文件，得到配置文件的路径
	 */
	private List<ConfigDataResolutionResult> resolve(ConfigDataLocationResolverContext locationResolverContext,
			Profiles profiles, ConfigDataLocation location) {
		try {
			return this.resolvers.resolve(locationResolverContext, location, profiles);
		}
		catch (ConfigDataNotFoundException ex) {
			handle(ex, location, null);
			return Collections.emptyList();
		}
	}

	private Map<ConfigDataResolutionResult, ConfigData> load(ConfigDataLoaderContext loaderContext,
			List<ConfigDataResolutionResult> candidates) throws IOException {
		Map<ConfigDataResolutionResult, ConfigData> result = new LinkedHashMap<>();
		// 从后往前遍历配置文件的引用
		for (int i = candidates.size() - 1; i >= 0; i--) {
			ConfigDataResolutionResult candidate = candidates.get(i);
			// 路径位置
			ConfigDataLocation location = candidate.getLocation();
			// 引用
			ConfigDataResource resource = candidate.getResource();
			if (resource.isOptional()) {
				// 判断是否可选
				// 是则添加到 optionalLocations
				this.optionalLocations.add(location);
			}
			// 判断如果已经加载过，则添加到 loadedLocations 中继续
			if (this.loaded.contains(resource)) {
				this.loadedLocations.add(location);
			}
			else {
				// 否则解析引用
				try {
					// 解析对应的配置文件引用，解析到 ConfigData 中
					ConfigData loaded = this.loaders.load(loaderContext, resource);
					if (loaded != null) {
						// 有解析结果，添加到 result 中
						// 并添加到 loaded 和 loadedLocations 中，避免重复加载
						this.loaded.add(resource);
						this.loadedLocations.add(location);
						result.put(candidate, loaded);
					}
				}
				catch (ConfigDataNotFoundException ex) {
					handle(ex, location, resource);
				}
			}
		}
		// 返回解析的结果
		return Collections.unmodifiableMap(result);
	}

	private void handle(ConfigDataNotFoundException ex, ConfigDataLocation location, ConfigDataResource resource) {
		if (ex instanceof ConfigDataResourceNotFoundException) {
			ex = ((ConfigDataResourceNotFoundException) ex).withLocation(location);
		}
		getNotFoundAction(location, resource).handle(this.logger, ex);
	}

	private ConfigDataNotFoundAction getNotFoundAction(ConfigDataLocation location, ConfigDataResource resource) {
		if (location.isOptional() || (resource != null && resource.isOptional())) {
			return ConfigDataNotFoundAction.IGNORE;
		}
		return this.notFoundAction;
	}

	Set<ConfigDataLocation> getLoadedLocations() {
		return this.loadedLocations;
	}

	Set<ConfigDataLocation> getOptionalLocations() {
		return this.optionalLocations;
	}

}
