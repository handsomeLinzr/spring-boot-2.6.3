package org.springframework.boot.mytest.controller;

import org.springframework.beans.factory.annotation.Value;
//import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * @author linzherong
 * @date 2026/3/1 23:29
 */
@RestController
@RequestMapping("/my-test")
//@RefreshScope
public class MyTestController {

	private static final DateTimeFormatter YYYY_MM_DD_HH_MM_SS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	@Value("${my.name}")
	private String name;

	/** 来自 Nacos 配置中心（dataId 与 spring.config.import 一致，如 my-test），支持动态刷新 */
	@Value("${useLocalCache:false}")
	private boolean useLocalCache;

	@GetMapping("/now")
	public String now() {
		System.out.println(name);
		return YYYY_MM_DD_HH_MM_SS.format(LocalDateTime.now());
	}

	@GetMapping("/nacos-config")
	public String nacosConfig() {
		return "useLocalCache=" + useLocalCache;
	}

}
