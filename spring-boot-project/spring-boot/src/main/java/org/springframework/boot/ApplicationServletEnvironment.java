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

import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.ConfigurablePropertyResolver;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.web.context.support.StandardServletEnvironment;

// 对应的 servlet-web 环境情况下创建的环境对象
/**
 * {@link StandardServletEnvironment} for typical use in a typical
 * {@link SpringApplication}.
 *
 * @author Phillip Webb
 */
class ApplicationServletEnvironment extends StandardServletEnvironment {


	// 这个对象对应父类的 propertyResolver = ConfigurationPropertySourcesPropertyResolver


	@Override
	protected String doGetActiveProfilesProperty() {
		return null;
	}

	@Override
	protected String doGetDefaultProfilesProperty() {
		return null;
	}

	// 重写了 createPropertyResolver 方法， servlet WEB 容器情况启动的环境对象
	// propertySources 是从 AbstractEnvironment 构造函数传来的空的 MutablePropertySources 对象
	@Override
	protected ConfigurablePropertyResolver createPropertyResolver(MutablePropertySources propertySources) {
		// 创建一个 ConfigurationPropertySourcesPropertyResolver 对象
		return ConfigurationPropertySources.createPropertyResolver(propertySources);
	}

}
