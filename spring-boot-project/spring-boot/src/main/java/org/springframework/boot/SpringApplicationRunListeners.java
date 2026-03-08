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

package org.springframework.boot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import org.apache.commons.logging.Log;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
import org.springframework.util.ReflectionUtils;

/**
 * A collection of {@link SpringApplicationRunListener}.
 *
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @author Chris Bono
 */
class SpringApplicationRunListeners {

	private final Log log;

	// 在启动的时候，调用 run 方法，从 SPI 机制中获取所有的 SpringApplicationRunListener 对象并进行实例化
	// 添加到这里 listeners
	// 默认是 EventPublishingRunListener
	private final List<SpringApplicationRunListener> listeners;

	private final ApplicationStartup applicationStartup;

	SpringApplicationRunListeners(Log log, Collection<? extends SpringApplicationRunListener> listeners,
			ApplicationStartup applicationStartup) {
		this.log = log;
		this.listeners = new ArrayList<>(listeners);
		this.applicationStartup = applicationStartup;
	}

	// 启动监听器
	void starting(ConfigurableBootstrapContext bootstrapContext, Class<?> mainApplicationClass) {
		// 启动监听
		// 遍历所有的 SpringApplicationRunListener 对象，调用 starting 方法
		// 这里会调用到 EventPublishingRunListener 对象，调用 starting 方法，会遍历到所有的监听器
		// bootstrapContext 是对应前边创建的 DefaultBootstrapContext
		doWithListeners("spring.boot.application.starting", (listener) -> listener.starting(bootstrapContext),
				(step) -> {
					if (mainApplicationClass != null) {
						step.tag("mainApplicationClass", mainApplicationClass.getName());
					}
				});
	}

	// 环境准备事件
	void environmentPrepared(ConfigurableBootstrapContext bootstrapContext, ConfigurableEnvironment environment) {
		doWithListeners("spring.boot.application.environment-prepared",
				(listener) -> listener.environmentPrepared(bootstrapContext, environment));
	}

	void contextPrepared(ConfigurableApplicationContext context) {
		doWithListeners("spring.boot.application.context-prepared", (listener) -> listener.contextPrepared(context));
	}

	void contextLoaded(ConfigurableApplicationContext context) {
		doWithListeners("spring.boot.application.context-loaded", (listener) -> listener.contextLoaded(context));
	}

	void started(ConfigurableApplicationContext context, Duration timeTaken) {
		doWithListeners("spring.boot.application.started", (listener) -> listener.started(context, timeTaken));
	}

	void ready(ConfigurableApplicationContext context, Duration timeTaken) {
		doWithListeners("spring.boot.application.ready", (listener) -> listener.ready(context, timeTaken));
	}

	void failed(ConfigurableApplicationContext context, Throwable exception) {
		doWithListeners("spring.boot.application.failed",
				(listener) -> callFailedListener(listener, context, exception), (step) -> {
					step.tag("exception", exception.getClass().toString());
					step.tag("message", exception.getMessage());
				});
	}

	private void callFailedListener(SpringApplicationRunListener listener, ConfigurableApplicationContext context,
			Throwable exception) {
		try {
			listener.failed(context, exception);
		}
		catch (Throwable ex) {
			if (exception == null) {
				ReflectionUtils.rethrowRuntimeException(ex);
			}
			if (this.log.isDebugEnabled()) {
				this.log.error("Error handling failed", ex);
			}
			else {
				String message = ex.getMessage();
				message = (message != null) ? message : "no error message";
				this.log.warn("Error handling failed (" + message + ")");
			}
		}
	}

	private void doWithListeners(String stepName, Consumer<SpringApplicationRunListener> listenerAction) {
		doWithListeners(stepName, listenerAction, null);
	}

	// 监听器处理
	// stepName 步骤名称
	// listenerAction 监听的处理过程
	// stepAction 步骤的处理过程
	private void doWithListeners(String stepName, Consumer<SpringApplicationRunListener> listenerAction,
			Consumer<StartupStep> stepAction) {
		// 处理步骤名称，默认
		StartupStep step = this.applicationStartup.start(stepName);
		// SpringApplicationRunListener 遍历监听器，调用 listenerAction 处理过程
		this.listeners.forEach(listenerAction);
		if (stepAction != null) {
			// 判断如果 stepAction 处理步骤不为空，调用 stepAction 处理过程
			stepAction.accept(step);
		}
		// 步骤结束，默认空处理
		step.end();
	}

}
