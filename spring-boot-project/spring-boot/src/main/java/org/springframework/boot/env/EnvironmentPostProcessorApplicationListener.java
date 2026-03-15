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

package org.springframework.boot.env;

import java.util.List;
import java.util.function.Function;

import org.springframework.boot.ConfigurableBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ResourceLoader;

/**
 * {@link SmartApplicationListener} used to trigger {@link EnvironmentPostProcessor
 * EnvironmentPostProcessors} registered in the {@code spring.factories} file.
 *
 * @author Phillip Webb
 * @since 2.4.0
 */
public class EnvironmentPostProcessorApplicationListener implements SmartApplicationListener, Ordered {

	/**
	 * The default order for the processor.
	 */
	public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

	private final DeferredLogs deferredLogs;

	private int order = DEFAULT_ORDER;

	// 获取要加载的类的过程，返回 ReflectionEnvironmentPostProcessorsFactory
	private final Function<ClassLoader, EnvironmentPostProcessorsFactory> postProcessorsFactory;

	// 构造函数，传进去  EnvironmentPostProcessorsFactory.fromSpringFactories(classLoader) 方法
	/**
	 * Create a new {@link EnvironmentPostProcessorApplicationListener} with
	 * {@link EnvironmentPostProcessor} classes loaded via {@code spring.factories}.
	 */
	public EnvironmentPostProcessorApplicationListener() {
		this((classLoader) -> EnvironmentPostProcessorsFactory.fromSpringFactories(classLoader), new DeferredLogs());
	}

	/**
	 * Create a new {@link EnvironmentPostProcessorApplicationListener} with post
	 * processors created by the given factory.
	 * @param postProcessorsFactory the post processors factory
	 */
	public EnvironmentPostProcessorApplicationListener(EnvironmentPostProcessorsFactory postProcessorsFactory) {
		this((classloader) -> postProcessorsFactory, new DeferredLogs());
	}

	EnvironmentPostProcessorApplicationListener(
			Function<ClassLoader, EnvironmentPostProcessorsFactory> postProcessorsFactory, DeferredLogs deferredLogs) {
		this.postProcessorsFactory = postProcessorsFactory;
		this.deferredLogs = deferredLogs;
	}

	// 支持的事件类型，都是上下文环境的事件
	@Override
	public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
		return ApplicationEnvironmentPreparedEvent.class.isAssignableFrom(eventType)
				|| ApplicationPreparedEvent.class.isAssignableFrom(eventType)
				|| ApplicationFailedEvent.class.isAssignableFrom(eventType);
	}

	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		if (event instanceof ApplicationEnvironmentPreparedEvent) {
			// 环境准备完成事件处理
			onApplicationEnvironmentPreparedEvent((ApplicationEnvironmentPreparedEvent) event);
		}
		if (event instanceof ApplicationPreparedEvent) {
			onApplicationPreparedEvent();
		}
		if (event instanceof ApplicationFailedEvent) {
			onApplicationFailedEvent();
		}
	}

	private void onApplicationEnvironmentPreparedEvent(ApplicationEnvironmentPreparedEvent event) {
		ConfigurableEnvironment environment = event.getEnvironment();
		SpringApplication application = event.getSpringApplication();
		// RandomValuePropertySourceEnvironmentPostProcessor
		// SystemEnvironmentPropertySourceEnvironmentPostProcessor
		// SpringApplicationJsonEnvironmentPostProcessor
		// CloudFoundryVcapEnvironmentPostProcessor
		// ConfigDataEnvironmentPostProcessor
		// IntegrationPropertiesEnvironmentPostProcessor
		// DebugAgentEnvironmentPostProcessor
		for (EnvironmentPostProcessor postProcessor : getEnvironmentPostProcessors(application.getResourceLoader(),
				// 实例化得到所有的 EnvironmentPostProcessor 类型的类
				event.getBootstrapContext())) {
			// 遍历调用 postProcessEnvironment 方法
			postProcessor.postProcessEnvironment(environment, application);
		}
	}

	private void onApplicationPreparedEvent() {
		finish();
	}

	private void onApplicationFailedEvent() {
		finish();
	}

	private void finish() {
		this.deferredLogs.switchOverAll();
	}

	List<EnvironmentPostProcessor> getEnvironmentPostProcessors(ResourceLoader resourceLoader,
			ConfigurableBootstrapContext bootstrapContext) {
		// 类加载器
		ClassLoader classLoader = (resourceLoader != null) ? resourceLoader.getClassLoader() : null;
		// 加载到 EnvironmentPostProcessor 类型对象
		EnvironmentPostProcessorsFactory postProcessorsFactory = this.postProcessorsFactory.apply(classLoader);
		// 实例化 EnvironmentPostProcessor 类实例
		return postProcessorsFactory.getEnvironmentPostProcessors(this.deferredLogs, bootstrapContext);
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	public void setOrder(int order) {
		this.order = order;
	}

}
