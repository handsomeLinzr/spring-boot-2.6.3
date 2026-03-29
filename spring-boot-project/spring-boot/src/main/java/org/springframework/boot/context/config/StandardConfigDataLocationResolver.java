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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;

import org.springframework.boot.context.config.LocationResourceLoader.ResourceType;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.log.LogMessage;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

// 标准默认的本地配置文件解析
/**
 * {@link ConfigDataLocationResolver} for standard locations.
 *
 * @author Madhura Bhave
 * @author Phillip Webb
 * @author Scott Frederick
 * @since 2.4.0
 */
public class StandardConfigDataLocationResolver
		implements ConfigDataLocationResolver<StandardConfigDataResource>, Ordered {

	// 配置文件前缀
	private static final String PREFIX = "resource:";

	static final String CONFIG_NAME_PROPERTY = "spring.config.name";

	// 默认的配置文件名
	private static final String[] DEFAULT_CONFIG_NAMES = { "application" };

	private static final Pattern URL_PREFIX = Pattern.compile("^([a-zA-Z][a-zA-Z0-9*]*?:)(.*$)");

	private static final Pattern EXTENSION_HINT_PATTERN = Pattern.compile("^(.*)\\[(\\.\\w+)\\](?!\\[)$");

	private static final String NO_PROFILE = null;

	private final Log logger;

	// 构造函数中，通过 Spring SPI 机制传进来实例化两个对象
	// PropertiesPropertySourceLoader
	// YamlPropertySourceLoader
	private final List<PropertySourceLoader> propertySourceLoaders;

	// 构造函数中设置，如果设置了 spring.config.name，则获取到对应的名称，没有则默认是 application
	// 没有后缀名
	private final String[] configNames;

	// 构造函数设置，LocationResourceLoader
	private final LocationResourceLoader resourceLoader;

	/**
	 * Create a new {@link StandardConfigDataLocationResolver} instance.
	 * @param logger the logger to use
	 * @param binder a binder backed by the initial {@link Environment}
	 * @param resourceLoader a {@link ResourceLoader} used to load resources
	 */
	public StandardConfigDataLocationResolver(Log logger, Binder binder, ResourceLoader resourceLoader) {
		this.logger = logger;
		// PropertiesPropertySourceLoader
		// YamlPropertySourceLoader
		this.propertySourceLoaders = SpringFactoriesLoader.loadFactories(PropertySourceLoader.class,
				getClass().getClassLoader());
		// 默认是 application
		this.configNames = getConfigNames(binder);
		//                                                resourceLoader = DefaultResourceLoader
		// resourceLoader = LocationResourceLoader(DefaultResourceLoader）
		this.resourceLoader = new LocationResourceLoader(resourceLoader);
	}

	/**
	 * 获取配置的名称，默认得到的是 application，没有后缀
	 * @param binder
	 * @return
	 */
	private String[] getConfigNames(Binder binder) {
		// 有 spring.config.name 则获取对应的名称，没有则获取默认的配置名，也就是 application
		String[] configNames = binder.bind(CONFIG_NAME_PROPERTY, String[].class).orElse(DEFAULT_CONFIG_NAMES);
		for (String configName : configNames) {
			validateConfigName(configName);
		}
		// 返回配置名称
		return configNames;
	}

	private void validateConfigName(String name) {
		Assert.state(!name.contains("*"), () -> "Config name '" + name + "' cannot contain '*'");
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	@Override
	public boolean isResolvable(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
		return true;
	}

	/**
	 * 1.根据给定的资源路径，组装成对应的配置文件路径引用
	 * 2.根据组装的路径引用，解析判断是否存在，存在则筛选出来
	 * 3.最后返回存在的配置文件
	 */
	@Override
	public List<StandardConfigDataResource> resolve(ConfigDataLocationResolverContext context,
			ConfigDataLocation location) throws ConfigDataNotFoundException {
		// location.split() ==> 将 location 按分号切分，切分成各个路径
		// getReferences 获取对应的路径下的，组装所有文件路径
		// 将得到的路径结果，判断是否存在，存在则添加到集合中返回
		return resolve(getReferences(context, location.split()));
	}

	/**
	 * 根据给定的路径列表，获取对应的配置文件路径引用，并返回
	 */
	private Set<StandardConfigDataReference> getReferences(ConfigDataLocationResolverContext context,
			ConfigDataLocation[] configDataLocations) {
		Set<StandardConfigDataReference> references = new LinkedHashSet<>();
		// 遍历所有的配置文件路径
		for (ConfigDataLocation configDataLocation : configDataLocations) {
			// 调用 getReferences 获取对应的引用，添加到 references 中
			references.addAll(getReferences(context, configDataLocation));
		}
		// 返回对应得到的所有引用
		return references;
	}

	/**
	 * 根据路径，获取可能的配置文件
	 */
	private Set<StandardConfigDataReference> getReferences(ConfigDataLocationResolverContext context,
			ConfigDataLocation configDataLocation) {
		// 获取资源路径
		String resourceLocation = getResourceLocation(context, configDataLocation);
		try {
			// 判断 resourceLocation 是否是一个文件夹，也就是 / 结尾
			if (isDirectory(resourceLocation)) {
				// 获取没有 profile 的配置文件夹
				return getReferencesForDirectory(configDataLocation, resourceLocation, NO_PROFILE);
			}
			// 不是文件夹，也就是不是 / 结果，直接处理这个文件
			return getReferencesForFile(configDataLocation, resourceLocation, NO_PROFILE);
		}
		catch (RuntimeException ex) {
			throw new IllegalStateException("Unable to load config data from '" + configDataLocation + "'", ex);
		}
	}

	/**
	 * 带文件标识的类型，解析配置文件得到存在的配置文件
	 */
	@Override
	public List<StandardConfigDataResource> resolveProfileSpecific(ConfigDataLocationResolverContext context,
			ConfigDataLocation location, Profiles profiles) {
		return resolve(getProfileSpecificReferences(context, location.split(), profiles));
	}

	/**
	 * 根据 profiles 处理数据得到配置文件引用
	 */
	private Set<StandardConfigDataReference> getProfileSpecificReferences(ConfigDataLocationResolverContext context,
			ConfigDataLocation[] configDataLocations, Profiles profiles) {
		Set<StandardConfigDataReference> references = new LinkedHashSet<>();
		// 遍历所有的文件标识，如 default/dev 等
		for (String profile : profiles) {
			// 遍历所有的配置文件路径
			for (ConfigDataLocation configDataLocation : configDataLocations) {
				// 获取对应的配置文件路径
				String resourceLocation = getResourceLocation(context, configDataLocation);
				// 根据文件标识，或者路径上的所有配置文件
				references.addAll(getReferences(configDataLocation, resourceLocation, profile));
			}
		}
		//  返回得到的配置文件
		return references;
	}

	// 获取配置文件路径
	private String getResourceLocation(ConfigDataLocationResolverContext context,
			ConfigDataLocation configDataLocation) {
		// 从 configDataLocation 获取到 value，并且去掉了 resource: 前缀
		String resourceLocation = configDataLocation.getNonPrefixedValue(PREFIX);
		// 判断是否是绝对路径，也就是  / 开头或者是 URL_PREFIX 这个正则表示式能匹配上，也就是一个URL，配置中心
		boolean isAbsolute = resourceLocation.startsWith("/") || URL_PREFIX.matcher(resourceLocation).matches();
		if (isAbsolute) {
			// 如果是绝对路径，直接返回
			return resourceLocation;
		}
		// 如果不是绝对路径，获取父 ConfigDataResource
		ConfigDataResource parent = context.getParent();
		if (parent instanceof StandardConfigDataResource) {
			String parentResourceLocation = ((StandardConfigDataResource) parent).getReference().getResourceLocation();
			String parentDirectory = parentResourceLocation.substring(0, parentResourceLocation.lastIndexOf("/") + 1);
			return parentDirectory + resourceLocation;
		}
		return resourceLocation;
	}

	/**
	 * 获取配置文件路径下的所有配置文件
	 */
	private Set<StandardConfigDataReference> getReferences(ConfigDataLocation configDataLocation,
			String resourceLocation, String profile) {
		// 文件夹
		if (isDirectory(resourceLocation)) {
			// 获取文件夹在，所有文件标识的配置文件
			return getReferencesForDirectory(configDataLocation, resourceLocation, profile);
		}
		// 文件
		return getReferencesForFile(configDataLocation, resourceLocation, profile);
	}

	/**
	 * 根据配置文件夹路径 + 配置文件的文件名 + 文件标识，解析得到可能的配置文件名称
	 */
	private Set<StandardConfigDataReference> getReferencesForDirectory(ConfigDataLocation configDataLocation,
			String directory, String profile) {
		Set<StandardConfigDataReference> references = new LinkedHashSet<>();
		// 遍历配置名称，和前缀组合成文件名添加，一般这里的配置名称是 application
		for (String name : this.configNames) {
			// 通过得到的解析器，拼装成所有可能的配置文件名称
			Deque<StandardConfigDataReference> referencesForName = getReferencesForConfigName(name, configDataLocation,
					directory, profile);
			// 添加到 references 中返回
			references.addAll(referencesForName);
		}
		return references;
	}

	/**
	 * 根据给定的文件名+文件夹位置+文件标识，组装出对应的配置文件名称
	 */
	private Deque<StandardConfigDataReference> getReferencesForConfigName(String name,
			ConfigDataLocation configDataLocation, String directory, String profile) {
		Deque<StandardConfigDataReference> references = new ArrayDeque<>();
		// 遍历解析器，这里有 properties 和 yml 两个
		for (PropertySourceLoader propertySourceLoader : this.propertySourceLoaders) {
			// 遍历解析器指定的后缀名，有 properties、xml 和 yml、ymal
			for (String extension : propertySourceLoader.getFileExtensions()) {
				StandardConfigDataReference reference = new StandardConfigDataReference(configDataLocation, directory,
						// directory + name 得到文件名
						directory + name, profile, extension, propertySourceLoader);
				if (!references.contains(reference)) {
					// 添加到前边，后来负载前边的
					references.addFirst(reference);
				}
			}
		}
		// 返回对应组装的配置文件
		return references;
	}

	/**
	 * 根据给定的文件名和指定的文件标识，获取对应后缀匹配上的解析器，并返回封装的 StandardConfigDataReference 对象
	 */
	private Set<StandardConfigDataReference> getReferencesForFile(ConfigDataLocation configDataLocation, String file,
			String profile) {
		Matcher extensionHintMatcher = EXTENSION_HINT_PATTERN.matcher(file);
		boolean extensionHintLocation = extensionHintMatcher.matches();
		if (extensionHintLocation) {
			file = extensionHintMatcher.group(1) + extensionHintMatcher.group(2);
		}
		// 遍历所有的解析器，这里有 properties 和 yml 两个解析器
		for (PropertySourceLoader propertySourceLoader : this.propertySourceLoaders) {
			// 返回符合条件的后缀名
			String extension = getLoadableFileExtension(propertySourceLoader, file);
			if (extension != null) {
				// 有符合条件的后缀名
				// 获取文件的 root 路径，也就是文件名去掉后缀名的那一段
				String root = file.substring(0, file.length() - extension.length() - 1);
				// 构建 StandardConfigDataReference 并返回
				StandardConfigDataReference reference = new StandardConfigDataReference(configDataLocation, null, root,
						profile, (!extensionHintLocation) ? extension : null, propertySourceLoader);
				return Collections.singleton(reference);
			}
		}
		throw new IllegalStateException("File extension is not known to any PropertySourceLoader. "
				+ "If the location is meant to reference a directory, it must end in '/' or File.separator");
	}

	/**
	 * 根据给定的具体解析器，判断给定的文件是否符合条件，返回符合条件的后缀名
	 */
	private String getLoadableFileExtension(PropertySourceLoader loader, String file) {
		// 遍历当前加载配置的后缀名
		for (String fileExtension : loader.getFileExtensions()) {
			// 判断给定的文件是否是以这个后缀结尾，是则返回
			if (StringUtils.endsWithIgnoreCase(file, fileExtension)) {
				return fileExtension;
			}
		}
		// 不是则返回 null
		return null;
	}

	private boolean isDirectory(String resourceLocation) {
		return resourceLocation.endsWith("/") || resourceLocation.endsWith(File.separator);
	}

	/**
	 * 根据给定的文件引用列表，判断是否有具体的文件资源对应
	 * 有则返回文件资源，没有则过滤掉
	 */
	private List<StandardConfigDataResource> resolve(Set<StandardConfigDataReference> references) {
		// 创建集合 list
		List<StandardConfigDataResource> resolved = new ArrayList<>();
		for (StandardConfigDataReference reference : references) {
			// resolve 解析，判断有存在则返回，不存在则返回空
			resolved.addAll(resolve(reference));
		}
		if (resolved.isEmpty()) {
			resolved.addAll(resolveEmptyDirectories(references));
		}
		return resolved;
	}

	private Collection<StandardConfigDataResource> resolveEmptyDirectories(
			Set<StandardConfigDataReference> references) {
		Set<StandardConfigDataResource> empty = new LinkedHashSet<>();
		for (StandardConfigDataReference reference : references) {
			if (reference.getDirectory() != null) {
				empty.addAll(resolveEmptyDirectories(reference));
			}
		}
		return empty;
	}

	private Set<StandardConfigDataResource> resolveEmptyDirectories(StandardConfigDataReference reference) {
		if (!this.resourceLoader.isPattern(reference.getResourceLocation())) {
			return resolveNonPatternEmptyDirectories(reference);
		}
		return resolvePatternEmptyDirectories(reference);
	}

	private Set<StandardConfigDataResource> resolveNonPatternEmptyDirectories(StandardConfigDataReference reference) {
		Resource resource = this.resourceLoader.getResource(reference.getDirectory());
		return (resource instanceof ClassPathResource || !resource.exists()) ? Collections.emptySet()
				: Collections.singleton(new StandardConfigDataResource(reference, resource, true));
	}

	private Set<StandardConfigDataResource> resolvePatternEmptyDirectories(StandardConfigDataReference reference) {
		Resource[] subdirectories = this.resourceLoader.getResources(reference.getDirectory(), ResourceType.DIRECTORY);
		ConfigDataLocation location = reference.getConfigDataLocation();
		if (!location.isOptional() && ObjectUtils.isEmpty(subdirectories)) {
			String message = String.format("Config data location '%s' contains no subdirectories", location);
			throw new ConfigDataLocationNotFoundException(location, message, null);
		}
		return Arrays.stream(subdirectories).filter(Resource::exists)
				.map((resource) -> new StandardConfigDataResource(reference, resource, true))
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	/**
	 * 根据给定的标准引用，查找对应的配置文件，存在则返回
	 * @param reference
	 * @return
	 */
	private List<StandardConfigDataResource> resolve(StandardConfigDataReference reference) {
		// 判断是否有通配符 *
		if (!this.resourceLoader.isPattern(reference.getResourceLocation())) {
			// 没有通配符的情况下，直接解析
			return resolveNonPattern(reference);
		}
		// 有通配符，则通配符情况下解析
		return resolvePattern(reference);
	}


	/**
	 * 处理给定具体的路径下，对应的配置文件资源
	 */
	private List<StandardConfigDataResource> resolveNonPattern(StandardConfigDataReference reference) {
		// 获取对应的具体文件
		Resource resource = this.resourceLoader.getResource(reference.getResourceLocation());
		if (!resource.exists() && reference.isSkippable()) {
			// 如果对应的配置文件不存在，且是能被跳过的，则返回空 list
			logSkippingResource(reference);
			return Collections.emptyList();
		}
		// 如果存在，则直接封装成 StandardConfigDataResource 返回
		return Collections.singletonList(createConfigResourceLocation(reference, resource));
	}

	/**
	 * 处理通配符情况下，对应给定的资源路径下的配置文件
	 */
	private List<StandardConfigDataResource> resolvePattern(StandardConfigDataReference reference) {
		List<StandardConfigDataResource> resolved = new ArrayList<>();
		// 获取通配符下所有符合条件的文件
		for (Resource resource : this.resourceLoader.getResources(reference.getResourceLocation(), ResourceType.FILE)) {
			// 判断如果文件不存在，且允许跳过，则跳过
			if (!resource.exists() && reference.isSkippable()) {
				logSkippingResource(reference);
			}
			else {
				// 文件存在，收集到 resolved 中后续返回
				resolved.add(createConfigResourceLocation(reference, resource));
			}
		}
		return resolved;
	}

	private void logSkippingResource(StandardConfigDataReference reference) {
		this.logger.trace(LogMessage.format("Skipping missing resource %s", reference));
	}

	private StandardConfigDataResource createConfigResourceLocation(StandardConfigDataReference reference,
			Resource resource) {
		return new StandardConfigDataResource(reference, resource);
	}

}
