package org.springframework.boot.mytest.controller;

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
public class MyTestController {

	private static final DateTimeFormatter YYYY_MM_DD_HH_MM_SS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	@GetMapping("/now")
	public String now() {
		return YYYY_MM_DD_HH_MM_SS.format(LocalDateTime.now());
	}

}
