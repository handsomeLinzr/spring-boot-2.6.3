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

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.util.Assert;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

/**
 * Strategy interface for loading resources from a location. Supports single resource and
 * simple wildcard directory patterns.
 *
 * @author Phillip Webb
 * @author Madhura Bhave
 */
class LocationResourceLoader {

	private static final Resource[] EMPTY_RESOURCES = {};

	private static final Comparator<File> FILE_PATH_COMPARATOR = Comparator.comparing(File::getAbsolutePath);

	private static final Comparator<File> FILE_NAME_COMPARATOR = Comparator.comparing(File::getName);

	// 默认是 DefaultResourceLoader
	private final ResourceLoader resourceLoader;

	/**
	 * Create a new {@link LocationResourceLoader} instance.
	 * @param resourceLoader the underlying resource loader
	 */
	LocationResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	/**
	 * Returns if the location contains a pattern.
	 * @param location the location to check
	 * @return if the location is a pattern
	 */
	boolean isPattern(String location) {
		return StringUtils.hasLength(location) && location.contains("*");
	}

	/**
	 * Get a single resource from a non-pattern location.
	 * @param location the location
	 * @return the resource
	 * @see #isPattern(String)
	 */
	Resource getResource(String location) {
		validateNonPattern(location);
		// 切换成标准路径名
		location = StringUtils.cleanPath(location);
		if (!ResourceUtils.isUrl(location)) {
			// 不是 url 的类型，则加上 file: 标识
			location = ResourceUtils.FILE_URL_PREFIX + location;
		}
		// 返回对应的文件资源
		return this.resourceLoader.getResource(location);
	}

	private void validateNonPattern(String location) {
		Assert.state(!isPattern(location), () -> String.format("Location '%s' must not be a pattern", location));
	}

	// 获取通配符能匹配上的所有的配置文件
	/**
	 * Get a multiple resources from a location pattern.
	 * @param location the location pattern
	 * @param type the type of resource to return
	 * @return the resources
	 * @see #isPattern(String)
	 */
	Resource[] getResources(String location, ResourceType type) {
		validatePattern(location, type);
		//获取通配符前边的部分
		String directoryPath = location.substring(0, location.indexOf("*/"));
		// 获取通配符后边的部分
		String fileName = location.substring(location.lastIndexOf("/") + 1);
		// 获取通配符前边的部分，直接拿到对应的 resource
		Resource resource = getResource(directoryPath);
		if (!resource.exists()) {
			// 不存在，则直接返回
			return EMPTY_RESOURCES;
		}
		// 得到对应的文件，这里也就是拿到 fd
		File file = getFile(location, resource);
		if (!file.isDirectory()) {
			// 非文件夹，返回空
			return EMPTY_RESOURCES;
		}
		// 获取文件夹下的所有文件
		File[] subDirectories = file.listFiles(this::isVisibleDirectory);
		if (subDirectories == null) {
			// 没有，则返回空
			return EMPTY_RESOURCES;
		}
		// 排序
		Arrays.sort(subDirectories, FILE_PATH_COMPARATOR);
		if (type == ResourceType.DIRECTORY) {
			// DIRECTORY，则直接返回所有文件
			return Arrays.stream(subDirectories).map(FileSystemResource::new).toArray(Resource[]::new);
		}
		List<Resource> resources = new ArrayList<>();
		// 创建名称过滤器，过滤名称用，名称就是通配符后边的部分
		FilenameFilter filter = (dir, name) -> name.equals(fileName);
		// 遍历文件夹下的所有子文件夹
		for (File subDirectory : subDirectories) {
			// 列出所有文件
			File[] files = subDirectory.listFiles(filter);
			if (files != null) {
				// 根据名称排序
				Arrays.sort(files, FILE_NAME_COMPARATOR);
				// 过滤，符合名称条件的则封装成 FileSystemResource 并添加到 resources 中
				Arrays.stream(files).map(FileSystemResource::new).forEach(resources::add);
			}
		}
		// 返回 resources 数组
		return resources.toArray(EMPTY_RESOURCES);
	}

	private void validatePattern(String location, ResourceType type) {
		Assert.state(isPattern(location), () -> String.format("Location '%s' must be a pattern", location));
		Assert.state(!location.startsWith(ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX),
				() -> String.format("Location '%s' cannot use classpath wildcards", location));
		Assert.state(StringUtils.countOccurrencesOf(location, "*") == 1,
				() -> String.format("Location '%s' cannot contain multiple wildcards", location));
		String directoryPath = (type != ResourceType.DIRECTORY) ? location.substring(0, location.lastIndexOf("/") + 1)
				: location;
		Assert.state(directoryPath.endsWith("*/"), () -> String.format("Location '%s' must end with '*/'", location));
	}

	private File getFile(String patternLocation, Resource resource) {
		try {
			return resource.getFile();
		}
		catch (Exception ex) {
			throw new IllegalStateException(
					"Unable to load config data resource from pattern '" + patternLocation + "'", ex);
		}
	}

	private boolean isVisibleDirectory(File file) {
		return file.isDirectory() && !file.getName().startsWith("..");
	}

	/**
	 * Resource types that can be returned.
	 */
	enum ResourceType {

		/**
		 * Return file resources.
		 */
		FILE,

		/**
		 * Return directory resources.
		 */
		DIRECTORY

	}

}
