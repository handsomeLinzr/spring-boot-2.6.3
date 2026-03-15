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

import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.util.StringUtils;

/**
 * A reference expanded from the original {@link ConfigDataLocation} that can ultimately
 * be resolved to one or more {@link StandardConfigDataResource resources}.
 *
 * @author Phillip Webb
 */
class StandardConfigDataReference {

	private final ConfigDataLocation configDataLocation;

	// 资源路径
	private final String resourceLocation;

	private final String directory;

	private final String profile;

	private final PropertySourceLoader propertySourceLoader;

	// 构造函数标准的配置文件数据引用
	/**
	 * Create a new {@link StandardConfigDataReference} instance.
	 * @param configDataLocation the original location passed to the resolver
	 * @param directory the directory of the resource or {@code null} if the reference is
	 * to a file
	 * @param root the root of the resource location
	 * @param profile the profile being loaded
	 * @param extension the file extension for the resource
	 * @param propertySourceLoader the property source loader that should be used for this
	 * reference
	 */
	StandardConfigDataReference(ConfigDataLocation configDataLocation, String directory, String root, String profile,
			// 后缀
			String extension, PropertySourceLoader propertySourceLoader) {
		this.configDataLocation = configDataLocation;
		// 配置文件前缀，如 dev、test、pro 之类的，会自己变成   -dev、-test 之类的，没有则为空
		String profileSuffix = (StringUtils.hasText(profile)) ? "-" + profile : "";
		// 文件完整路径，如果有 profileSuffix 文件标识则加上
		this.resourceLocation = root + profileSuffix + ((extension != null) ? "." + extension : "");
		this.directory = directory;
		// 文件
		this.profile = profile;
		this.propertySourceLoader = propertySourceLoader;
	}

	ConfigDataLocation getConfigDataLocation() {
		return this.configDataLocation;
	}

	String getResourceLocation() {
		return this.resourceLocation;
	}

	boolean isMandatoryDirectory() {
		return !this.configDataLocation.isOptional() && this.directory != null;
	}

	String getDirectory() {
		return this.directory;
	}

	String getProfile() {
		return this.profile;
	}

	/**
	 * 判断能否跳过
	 */
	boolean isSkippable() {
		//    是否是 optional 的                        是否是直接文件不为空              是否文件标识不为空
		return this.configDataLocation.isOptional() || this.directory != null || this.profile != null;
	}

	PropertySourceLoader getPropertySourceLoader() {
		return this.propertySourceLoader;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if ((obj == null) || (getClass() != obj.getClass())) {
			return false;
		}
		StandardConfigDataReference other = (StandardConfigDataReference) obj;
		return this.resourceLocation.equals(other.resourceLocation);
	}

	@Override
	public int hashCode() {
		return this.resourceLocation.hashCode();
	}

	@Override
	public String toString() {
		return this.resourceLocation;
	}

}
