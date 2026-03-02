/*
 * Copyright 2012-2020 the original author or authors.
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;

import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

/**
 * Class used by {@link SpringApplication} to print the application banner.
 *
 * @author Phillip Webb
 */
class SpringApplicationBannerPrinter {

	static final String BANNER_LOCATION_PROPERTY = "spring.banner.location";

	static final String BANNER_IMAGE_LOCATION_PROPERTY = "spring.banner.image.location";

	static final String DEFAULT_BANNER_LOCATION = "banner.txt";

	static final String[] IMAGE_EXTENSION = { "gif", "jpg", "png" };

	private static final Banner DEFAULT_BANNER = new SpringBootBanner();

	private final ResourceLoader resourceLoader;

	private final Banner fallbackBanner;

	SpringApplicationBannerPrinter(ResourceLoader resourceLoader, Banner fallbackBanner) {
		this.resourceLoader = resourceLoader;
		this.fallbackBanner = fallbackBanner;
	}

	Banner print(Environment environment, Class<?> sourceClass, Log logger) {
		Banner banner = getBanner(environment);
		try {
			logger.info(createStringFromBanner(banner, environment, sourceClass));
		}
		catch (UnsupportedEncodingException ex) {
			logger.warn("Failed to create String for banner", ex);
		}
		return new PrintedBanner(banner, sourceClass);
	}

	Banner print(Environment environment, Class<?> sourceClass, PrintStream out) {
		// 获取 banner
		Banner banner = getBanner(environment);
		banner.printBanner(environment, sourceClass, out);
		return new PrintedBanner(banner, sourceClass);
	}

	private Banner getBanner(Environment environment) {
		Banners banners = new Banners();
		// 获取图片类型的banner
		banners.addIfNotNull(getImageBanner(environment));
		// 获取txt类型的banner
		banners.addIfNotNull(getTextBanner(environment));
		// 判断是否 banner 有值，不为空
		if (banners.hasAtLeastOneBanner()) {
			// 返回 banners
			return banners;
		}
		// 如果没有 banners
		if (this.fallbackBanner != null) {
			return this.fallbackBanner;
		}
		// 返回默认的 banner，即 SpringBootBanner
		// Springboot 启动后显示那个，就是这里默认的
		return DEFAULT_BANNER;
	}

	private Banner getTextBanner(Environment environment) {
		// 尝试获取 spring.banner.location 路径下的 banner.txt
		String location = environment.getProperty(BANNER_LOCATION_PROPERTY, DEFAULT_BANNER_LOCATION);
		// 获取资源
		Resource resource = this.resourceLoader.getResource(location);
		try {
			// 如果资源存在
			if (resource.exists() && !resource.getURL().toExternalForm().contains("liquibase-core")) {
				// 返回这个资源
				return new ResourceBanner(resource);
			}
		}
		catch (IOException ex) {
			// Ignore
		}
		// 否则返回空
		return null;
	}

	private Banner getImageBanner(Environment environment) {
		// 从环境中获取 banner 的路径，配置是 spring.banner.image.location
		String location = environment.getProperty(BANNER_IMAGE_LOCATION_PROPERTY);
		if (StringUtils.hasLength(location)) {
			// 如果有配置，加载资源
			Resource resource = this.resourceLoader.getResource(location);
			// 如果有这个路径对应的资源，则返回 ImageBanner(resource)，没有则返回 null
			return resource.exists() ? new ImageBanner(resource) : null;
		}
		// 遍历图片的类型扩展
		for (String ext : IMAGE_EXTENSION) {
			// 获取当前 classpath 下的 banner.xxx 文件
			Resource resource = this.resourceLoader.getResource("banner." + ext);
			// 如果有，则返回对应的 ImageBanner
			if (resource.exists()) {
				return new ImageBanner(resource);
			}
		}
		// 没有则返回空
		return null;
	}

	private String createStringFromBanner(Banner banner, Environment environment, Class<?> mainApplicationClass)
			throws UnsupportedEncodingException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		banner.printBanner(environment, mainApplicationClass, new PrintStream(baos));
		String charset = environment.getProperty("spring.banner.charset", "UTF-8");
		return baos.toString(charset);
	}

	/**
	 * {@link Banner} comprised of other {@link Banner Banners}.
	 */
	private static class Banners implements Banner {

		// 所有加载到的 banner
		private final List<Banner> banners = new ArrayList<>();

		void addIfNotNull(Banner banner) {
			if (banner != null) {
				this.banners.add(banner);
			}
		}

		boolean hasAtLeastOneBanner() {
			return !this.banners.isEmpty();
		}

		@Override
		public void printBanner(Environment environment, Class<?> sourceClass, PrintStream out) {
			for (Banner banner : this.banners) {
				banner.printBanner(environment, sourceClass, out);
			}
		}

	}

	/**
	 * Decorator that allows a {@link Banner} to be printed again without needing to
	 * specify the source class.
	 */
	private static class PrintedBanner implements Banner {

		private final Banner banner;

		private final Class<?> sourceClass;

		PrintedBanner(Banner banner, Class<?> sourceClass) {
			this.banner = banner;
			this.sourceClass = sourceClass;
		}

		@Override
		public void printBanner(Environment environment, Class<?> sourceClass, PrintStream out) {
			sourceClass = (sourceClass != null) ? sourceClass : this.sourceClass;
			this.banner.printBanner(environment, sourceClass, out);
		}

	}

}
