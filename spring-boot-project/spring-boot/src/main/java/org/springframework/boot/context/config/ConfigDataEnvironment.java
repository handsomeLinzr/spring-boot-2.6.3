/*
 * Copyright 2012-2022 the original author or authors.
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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;

import org.springframework.boot.BootstrapRegistry.InstanceSupplier;
import org.springframework.boot.BootstrapRegistry.Scope;
import org.springframework.boot.ConfigurableBootstrapContext;
import org.springframework.boot.DefaultPropertiesPropertySource;
import org.springframework.boot.context.config.ConfigDataEnvironmentContributors.BinderOption;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.log.LogMessage;
import org.springframework.util.StringUtils;

// 寻找和加载配置文件
/**
 * Wrapper around a {@link ConfigurableEnvironment} that can be used to import and apply
 * {@link ConfigData}. Configures the initial set of
 * {@link ConfigDataEnvironmentContributors} by wrapping property sources from the Spring
 * {@link Environment} and adding the initial set of locations.
 * <p>
 * The initial locations can be influenced via the {@link #LOCATION_PROPERTY},
 * {@value #ADDITIONAL_LOCATION_PROPERTY} and {@value #IMPORT_PROPERTY} properties. If no
 * explicit properties are set, the {@link #DEFAULT_SEARCH_LOCATIONS} will be used.
 *
 * @author Phillip Webb
 * @author Madhura Bhave
 */
class ConfigDataEnvironment {

	// 用来覆盖配置文件的位置
	/**
	 * Property used override the imported locations.
	 */
	static final String LOCATION_PROPERTY = "spring.config.location";

	// 增加的配置文件
	/**
	 * Property used to provide additional locations to import.
	 */
	static final String ADDITIONAL_LOCATION_PROPERTY = "spring.config.additional-location";

	// 用来导入其他配置的属性设置
	/**
	 * Property used to provide additional locations to import.
	 */
	static final String IMPORT_PROPERTY = "spring.config.import";

	/**
	 * Property used to determine what action to take when a
	 * {@code ConfigDataNotFoundAction} is thrown.
	 * @see ConfigDataNotFoundAction
	 */
	static final String ON_NOT_FOUND_PROPERTY = "spring.config.on-not-found";

	// 没有配置 spring.config.location 的情况下，默认的配置文件搜索路径
	/**
	 * Default search locations used if not {@link #LOCATION_PROPERTY} is found.
	 */
	static final ConfigDataLocation[] DEFAULT_SEARCH_LOCATIONS;
	static {
		// 默认的配置文件加载位置
		List<ConfigDataLocation> locations = new ArrayList<>();
		// classpath 下的路径
		locations.add(ConfigDataLocation.of("optional:classpath:/;optional:classpath:/config/"));
		// 本地文件路径
		locations.add(ConfigDataLocation.of("optional:file:./;optional:file:./config/;optional:file:./config/*/"));
		DEFAULT_SEARCH_LOCATIONS = locations.toArray(new ConfigDataLocation[0]);
	}

	private static final ConfigDataLocation[] EMPTY_LOCATIONS = new ConfigDataLocation[0];

	private static final Bindable<ConfigDataLocation[]> CONFIG_DATA_LOCATION_ARRAY = Bindable
			.of(ConfigDataLocation[].class);

	private static final Bindable<List<String>> STRING_LIST = Bindable.listOf(String.class);

	private static final BinderOption[] ALLOW_INACTIVE_BINDING = {};

	private static final BinderOption[] DENY_INACTIVE_BINDING = { BinderOption.FAIL_ON_BIND_TO_INACTIVE_SOURCE };

	private final DeferredLogFactory logFactory;

	private final Log logger;

	// 获取不到配置文件时候的处理方式？
	private final ConfigDataNotFoundAction notFoundAction;

	// bootstrap 上下文，构造函数的时候传进来
	private final ConfigurableBootstrapContext bootstrapContext;

	// 构造方法的时候传进来，环境配置
	private final ConfigurableEnvironment environment;

	// 配置文件资源解析器
	// 再构造方法的时候传进来
	// 其中有属性 resolvers 存了
	// 		ConfigTreeConfigDataLocationResolver 和
	// 		StandardConfigDataLocationResolver
	private final ConfigDataLocationResolvers resolvers;

	// 默认空集合
	private final Collection<String> additionalProfiles;

	// 默认空
	private final ConfigDataEnvironmentUpdateListener environmentUpdateListener;

	// 加载器，ConfigDataLoaders 对象
	// 封装了 	loaders：
	//		ConfigTreeConfigDataLoader
	// 		StandardConfigDataLoader
	private final ConfigDataLoaders loaders;

	// ConfigDataEnvironmentContributors 对象，内部不仅封装了配置源 + 2个contributors
	// 后续的配置文件查找可以直接从这里拿
	private final ConfigDataEnvironmentContributors contributors;

	/**
	 * Create a new {@link ConfigDataEnvironment} instance.
	 * @param logFactory the deferred log factory
	 * @param bootstrapContext the bootstrap context
	 * @param environment the Spring {@link Environment}.
	 * @param resourceLoader {@link ResourceLoader} to load resource locations
	 * @param additionalProfiles any additional profiles to activate
	 * @param environmentUpdateListener optional
	 * {@link ConfigDataEnvironmentUpdateListener} that can be used to track
	 * {@link Environment} updates.
	 */
	ConfigDataEnvironment(DeferredLogFactory logFactory, ConfigurableBootstrapContext bootstrapContext,
			ConfigurableEnvironment environment, ResourceLoader resourceLoader, Collection<String> additionalProfiles,
			ConfigDataEnvironmentUpdateListener environmentUpdateListener) {
		Binder binder = Binder.get(environment);
		UseLegacyConfigProcessingException.throwIfRequested(binder);
		this.logFactory = logFactory;
		this.logger = logFactory.getLog(getClass());
		this.notFoundAction = binder.bind(ON_NOT_FOUND_PROPERTY, ConfigDataNotFoundAction.class)
				.orElse(ConfigDataNotFoundAction.FAIL);
		this.bootstrapContext = bootstrapContext;
		// 当前环境
		this.environment = environment;
		// 资源解析器
		this.resolvers = createConfigDataLocationResolvers(logFactory, bootstrapContext, binder, resourceLoader);
		// 默认空集合，扩展用
		this.additionalProfiles = additionalProfiles;
		// 默认空
		this.environmentUpdateListener = (environmentUpdateListener != null) ? environmentUpdateListener
				: ConfigDataEnvironmentUpdateListener.NONE;
		// 加载器
		this.loaders = new ConfigDataLoaders(logFactory, bootstrapContext, resourceLoader.getClassLoader());
		// 创建 ConfigDataEnvironmentContributors
		this.contributors = createContributors(binder);
	}

	/**
	 * 创建配置资源解析器
	 * @param logFactory
	 * @param bootstrapContext
	 * @param binder
	 * @param resourceLoader
	 * @return
	 */
	protected ConfigDataLocationResolvers createConfigDataLocationResolvers(DeferredLogFactory logFactory,
			ConfigurableBootstrapContext bootstrapContext, Binder binder, ResourceLoader resourceLoader) {
		// 返回 ConfigDataLocationResolvers
		// 其中有属性 resolvers 存了 ConfigTreeConfigDataLocationResolver 和 StandardConfigDataLocationResolver
		return new ConfigDataLocationResolvers(logFactory, bootstrapContext, binder, resourceLoader);
	}

	/**
	 * 创建一个 ConfigDataEnvironmentContributors，初始化配置源集合
	 * @param binder
	 * @return
	 */
	private ConfigDataEnvironmentContributors createContributors(Binder binder) {
		this.logger.trace("Building config data environment contributors");
		// 当前配置环境的配置源，读取已有的配置
		MutablePropertySources propertySources = this.environment.getPropertySources();
		// 创建 list
		List<ConfigDataEnvironmentContributor> contributors = new ArrayList<>(propertySources.size() + 10);
		PropertySource<?> defaultPropertySource = null;
		// 遍历已有的配置
		for (PropertySource<?> propertySource : propertySources) {
			if (DefaultPropertiesPropertySource.hasMatchingName(propertySource)) {
				// 判断是否有名称为 defaultProperties，默认都没有
				defaultPropertySource = propertySource;
			}
			else {
				this.logger.trace(LogMessage.format("Creating wrapped config data contributor for '%s'",
						propertySource.getName()));
				// 默认都走这里，将 propertySource 封装成 ConfigDataEnvironmentContributor
				// 添加到 contributors 中
				contributors.add(ConfigDataEnvironmentContributor.ofExisting(propertySource));
			}
		}
		// 加载 import 配置，一起加到 contributors 中
		// 方便后边统一处理
		// 这里一般添加两个，分别是对应两种路径，分别是
		// optional:classpath:/;optional:classpath:/config/
		// optional:file:./;optional:file:./config/;optional:file:./config/*/
		contributors.addAll(getInitialImportContributors(binder));
		if (defaultPropertySource != null) {
			this.logger.trace("Creating wrapped config data contributor for default property source");
			contributors.add(ConfigDataEnvironmentContributor.ofExisting(defaultPropertySource));
		}
		// 创建 ConfigDataEnvironmentContributors 对象返回
		return createContributors(contributors);
	}

	protected ConfigDataEnvironmentContributors createContributors(
			List<ConfigDataEnvironmentContributor> contributors) {
		// 创建
		return new ConfigDataEnvironmentContributors(this.logFactory, this.bootstrapContext, contributors);
	}

	ConfigDataEnvironmentContributors getContributors() {
		return this.contributors;
	}

	// 添加配置字文件的默认位置
	private List<ConfigDataEnvironmentContributor> getInitialImportContributors(Binder binder) {
		List<ConfigDataEnvironmentContributor> initialContributors = new ArrayList<>();
		//																		spring.config.import    空配置
		addInitialImportContributors(initialContributors, bindLocations(binder, IMPORT_PROPERTY, EMPTY_LOCATIONS));
		addInitialImportContributors(initialContributors,
				//                    spring.config.additional-location
				bindLocations(binder, ADDITIONAL_LOCATION_PROPERTY, EMPTY_LOCATIONS));
		addInitialImportContributors(initialContributors,
				//					  spring.config.location
				bindLocations(binder, LOCATION_PROPERTY, DEFAULT_SEARCH_LOCATIONS));
		return initialContributors;
	}

	// 配置绑定
	private ConfigDataLocation[] bindLocations(Binder binder, String propertyName, ConfigDataLocation[] other) {
		return binder.bind(propertyName, CONFIG_DATA_LOCATION_ARRAY).orElse(other);
	}

	// 配置的添加顺序，倒序
	private void addInitialImportContributors(List<ConfigDataEnvironmentContributor> initialContributors,
			ConfigDataLocation[] locations) {
		for (int i = locations.length - 1; i >= 0; i--) {
			initialContributors.add(createInitialImportContributor(locations[i]));
		}
	}

	// 创建一个 ConfigDataEnvironmentContributor，表示是 import 进来的
	private ConfigDataEnvironmentContributor createInitialImportContributor(ConfigDataLocation location) {
		this.logger.trace(LogMessage.format("Adding initial config data import from location '%s'", location));
		return ConfigDataEnvironmentContributor.ofInitialImport(location);
	}

	/**
	 * 处理所有的配置属性
	 */
	/**
	 * Process all contributions and apply any newly imported property sources to the
	 * {@link Environment}.
	 */
	void processAndApply() {
		// 构建导入器
		ConfigDataImporter importer = new ConfigDataImporter(this.logFactory, this.notFoundAction, this.resolvers,
				this.loaders);
		// 0.注册 binder
		registerBootstrapBinder(this.contributors, null, DENY_INACTIVE_BINDING);

		// 1 处理默认配置位置的配置文件，默认是 application.properties/xml/yml/ymal
		ConfigDataEnvironmentContributors contributors = processInitial(this.contributors, importer);

		ConfigDataActivationContext activationContext = createActivationContext(
				contributors.getBinder(null, BinderOption.FAIL_ON_BIND_TO_INACTIVE_SOURCE));
		// 2 处理非 profile 配置
		contributors = processWithoutProfiles(contributors, importer, activationContext);

		// 加载 profile，包括 default 和 active
		activationContext = withProfiles(contributors, activationContext);

		// 3 处理有指定 profile 的情况，处理配置文件的解析
		contributors = processWithProfiles(contributors, importer, activationContext);

		// 4 应用配置到 Environment
		applyToEnvironment(contributors, activationContext, importer.getLoadedLocations(),
				importer.getOptionalLocations());
	}

	/**
	 * 加载 import 的配置
	 * @param contributors
	 * @param importer
	 * @return
	 */
	private ConfigDataEnvironmentContributors processInitial(ConfigDataEnvironmentContributors contributors,
			ConfigDataImporter importer) {
		this.logger.trace("Processing initial config data environment contributors without activation context");
		// 加载默认配置，没有 profiles 标识的情况
		contributors = contributors.withProcessedImports(importer, null);
		// 注册 binder
		registerBootstrapBinder(contributors, null, DENY_INACTIVE_BINDING);
		return contributors;
	}

	private ConfigDataActivationContext createActivationContext(Binder initialBinder) {
		this.logger.trace("Creating config data activation context from initial contributions");
		try {
			return new ConfigDataActivationContext(this.environment, initialBinder);
		}
		catch (BindException ex) {
			if (ex.getCause() instanceof InactiveConfigDataAccessException) {
				throw (InactiveConfigDataAccessException) ex.getCause();
			}
			throw ex;
		}
	}

	/**
	 * 配置 profiles
	 * @param contributors
	 * @param importer
	 * @param activationContext
	 * @return
	 */
	private ConfigDataEnvironmentContributors processWithoutProfiles(ConfigDataEnvironmentContributors contributors,
			ConfigDataImporter importer, ConfigDataActivationContext activationContext) {
		this.logger.trace("Processing config data environment contributors with initial activation context");
		contributors = contributors.withProcessedImports(importer, activationContext);
		registerBootstrapBinder(contributors, activationContext, DENY_INACTIVE_BINDING);
		return contributors;
	}

	/**
	 * 给 activationContext 设置对应的 profiles，包括 default、active、还有额外添加的
	 * @param contributors
	 * @param activationContext
	 * @return
	 */
	private ConfigDataActivationContext withProfiles(ConfigDataEnvironmentContributors contributors,
			ConfigDataActivationContext activationContext) {
		this.logger.trace("Deducing profiles from current config data environment contributors");
		Binder binder = contributors.getBinder(activationContext,
				(contributor) -> !contributor.hasConfigDataOption(ConfigData.Option.IGNORE_PROFILES),
				BinderOption.FAIL_ON_BIND_TO_INACTIVE_SOURCE);
		try {
			Set<String> additionalProfiles = new LinkedHashSet<>(this.additionalProfiles);
			additionalProfiles.addAll(getIncludedProfiles(contributors, activationContext));
			// 从环境中获取激活的配置标识，添加到 additionalProfiles 中，返回
			Profiles profiles = new Profiles(this.environment, binder, additionalProfiles);
			// 设置 profiles 进去
			return activationContext.withProfiles(profiles);
		}
		catch (BindException ex) {
			if (ex.getCause() instanceof InactiveConfigDataAccessException) {
				throw (InactiveConfigDataAccessException) ex.getCause();
			}
			throw ex;
		}
	}

	private Collection<? extends String> getIncludedProfiles(ConfigDataEnvironmentContributors contributors,
			ConfigDataActivationContext activationContext) {
		PlaceholdersResolver placeholdersResolver = new ConfigDataEnvironmentContributorPlaceholdersResolver(
				contributors, activationContext, null, true);
		Set<String> result = new LinkedHashSet<>();
		for (ConfigDataEnvironmentContributor contributor : contributors) {
			ConfigurationPropertySource source = contributor.getConfigurationPropertySource();
			if (source != null && !contributor.hasConfigDataOption(ConfigData.Option.IGNORE_PROFILES)) {
				Binder binder = new Binder(Collections.singleton(source), placeholdersResolver);
				binder.bind(Profiles.INCLUDE_PROFILES, STRING_LIST).ifBound((includes) -> {
					if (!contributor.isActive(activationContext)) {
						InactiveConfigDataAccessException.throwIfPropertyFound(contributor, Profiles.INCLUDE_PROFILES);
						InactiveConfigDataAccessException.throwIfPropertyFound(contributor,
								Profiles.INCLUDE_PROFILES.append("[0]"));
					}
					result.addAll(includes);
				});
			}
		}
		return result;
	}

	/**
	 * 处理带文件标识的配置
	 * @param contributors
	 * @param importer
	 * @param activationContext
	 * @return
	 */
	private ConfigDataEnvironmentContributors processWithProfiles(ConfigDataEnvironmentContributors contributors,
			ConfigDataImporter importer, ConfigDataActivationContext activationContext) {
		this.logger.trace("Processing config data environment contributors with profile activation context");
		contributors = contributors.withProcessedImports(importer, activationContext);
		registerBootstrapBinder(contributors, activationContext, ALLOW_INACTIVE_BINDING);
		return contributors;
	}

	private void registerBootstrapBinder(ConfigDataEnvironmentContributors contributors,
			ConfigDataActivationContext activationContext, BinderOption... binderOptions) {
		this.bootstrapContext.register(Binder.class, InstanceSupplier
				.from(() -> contributors.getBinder(activationContext, binderOptions)).withScope(Scope.PROTOTYPE));
	}
    // 应用到环境
	private void applyToEnvironment(ConfigDataEnvironmentContributors contributors,
			ConfigDataActivationContext activationContext, Set<ConfigDataLocation> loadedLocations,
			Set<ConfigDataLocation> optionalLocations) {
		// 校验
		checkForInvalidProperties(contributors);
		checkMandatoryLocations(contributors, activationContext, loadedLocations, optionalLocations);
		// 获取环境配置
		MutablePropertySources propertySources = this.environment.getPropertySources();
		// 将解析的结果 contributors 添加到 propertySources 中
		// 也就是添加到环境中
		applyContributor(contributors, activationContext, propertySources);
		// 判断如果有默认的配置 defaultProperties，则放到最后，作为默认的配置
		DefaultPropertiesPropertySource.moveToEnd(propertySources);
		// 获取对应的 profiles
		Profiles profiles = activationContext.getProfiles();

		// 日志
		this.logger.trace(LogMessage.format("Setting default profiles: %s", profiles.getDefault()));
		// 设置默认的文件名
		this.environment.setDefaultProfiles(StringUtils.toStringArray(profiles.getDefault()));
		// 日志追踪
		this.logger.trace(LogMessage.format("Setting active profiles: %s", profiles.getActive()));
		// 设置激活的 profiles
		this.environment.setActiveProfiles(StringUtils.toStringArray(profiles.getActive()));
		// 事件监听
		this.environmentUpdateListener.onSetProfiles(profiles);
	}

	private void applyContributor(ConfigDataEnvironmentContributors contributors,
			ConfigDataActivationContext activationContext, MutablePropertySources propertySources) {
		this.logger.trace("Applying config data environment contributions");
		for (ConfigDataEnvironmentContributor contributor : contributors) {
			PropertySource<?> propertySource = contributor.getPropertySource();
			if (contributor.getKind() == ConfigDataEnvironmentContributor.Kind.BOUND_IMPORT && propertySource != null) {
				if (!contributor.isActive(activationContext)) {
					this.logger.trace(
							LogMessage.format("Skipping inactive property source '%s'", propertySource.getName()));
				}
				else {
					this.logger
							.trace(LogMessage.format("Adding imported property source '%s'", propertySource.getName()));
					// 添加到最后
					propertySources.addLast(propertySource);
					this.environmentUpdateListener.onPropertySourceAdded(propertySource, contributor.getLocation(),
							contributor.getResource());
				}
			}
		}
	}

	private void checkForInvalidProperties(ConfigDataEnvironmentContributors contributors) {
		for (ConfigDataEnvironmentContributor contributor : contributors) {
			InvalidConfigDataPropertyException.throwOrWarn(this.logger, contributor);
		}
	}

	private void checkMandatoryLocations(ConfigDataEnvironmentContributors contributors,
			ConfigDataActivationContext activationContext, Set<ConfigDataLocation> loadedLocations,
			Set<ConfigDataLocation> optionalLocations) {
		Set<ConfigDataLocation> mandatoryLocations = new LinkedHashSet<>();
		for (ConfigDataEnvironmentContributor contributor : contributors) {
			if (contributor.isActive(activationContext)) {
				mandatoryLocations.addAll(getMandatoryImports(contributor));
			}
		}
		for (ConfigDataEnvironmentContributor contributor : contributors) {
			if (contributor.getLocation() != null) {
				mandatoryLocations.remove(contributor.getLocation());
			}
		}
		mandatoryLocations.removeAll(loadedLocations);
		mandatoryLocations.removeAll(optionalLocations);
		if (!mandatoryLocations.isEmpty()) {
			for (ConfigDataLocation mandatoryLocation : mandatoryLocations) {
				this.notFoundAction.handle(this.logger, new ConfigDataLocationNotFoundException(mandatoryLocation));
			}
		}
	}

	private Set<ConfigDataLocation> getMandatoryImports(ConfigDataEnvironmentContributor contributor) {
		List<ConfigDataLocation> imports = contributor.getImports();
		Set<ConfigDataLocation> mandatoryLocations = new LinkedHashSet<>(imports.size());
		for (ConfigDataLocation location : imports) {
			if (!location.isOptional()) {
				mandatoryLocations.add(location);
			}
		}
		return mandatoryLocations;
	}

}
