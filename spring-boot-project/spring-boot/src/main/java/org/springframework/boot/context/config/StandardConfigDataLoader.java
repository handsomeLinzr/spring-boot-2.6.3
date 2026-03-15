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
import java.util.List;

import org.springframework.boot.context.config.ConfigData.Option;
import org.springframework.boot.context.config.ConfigData.PropertySourceOptions;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginTrackedResource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;

/**
 * {@link ConfigDataLoader} for {@link Resource} backed locations.
 *
 * @author Phillip Webb
 * @author Madhura Bhave
 * @since 2.4.0
 */
public class StandardConfigDataLoader implements ConfigDataLoader<StandardConfigDataResource> {

	private static final PropertySourceOptions PROFILE_SPECIFIC = PropertySourceOptions.always(Option.PROFILE_SPECIFIC);

	private static final PropertySourceOptions NON_PROFILE_SPECIFIC = PropertySourceOptions.ALWAYS_NONE;

	/**
	 * 自定义加载配置文件引用的过程
	 */
	@Override
	public ConfigData load(ConfigDataLoaderContext context, StandardConfigDataResource resource)
			throws IOException, ConfigDataNotFoundException {
		if (resource.isEmptyDirectory()) {
			return ConfigData.EMPTY;
		}
		// 检查配置资源不为空
		ConfigDataResourceNotFoundException.throwIfDoesNotExist(resource, resource.getResource());
		// 获取配置资源的引用
		StandardConfigDataReference reference = resource.getReference();

		// 转成原始的资源类型
		Resource originTrackedResource = OriginTrackedResource.of(resource.getResource(),
				Origin.from(reference.getConfigDataLocation()));
		// 组装当前资源的名称
		String name = String.format("Config resource '%s' via location '%s'", resource,
				reference.getConfigDataLocation());
		// 调用具体的资源加载器，加载资源，并封装成 PropertySource 集合
		// 默认两个加载器，PropertiesPropertySourceLoader 和 YamlPropertySourceLoader
		List<PropertySource<?>> propertySources = reference.getPropertySourceLoader().load(name, originTrackedResource);  // 解析配置文件，得到 PropertySource
		PropertySourceOptions options = (resource.getProfile() != null) ? PROFILE_SPECIFIC : NON_PROFILE_SPECIFIC;
		// 封装成 ConfigData 对象并返回
		return new ConfigData(propertySources, options);
	}

}
