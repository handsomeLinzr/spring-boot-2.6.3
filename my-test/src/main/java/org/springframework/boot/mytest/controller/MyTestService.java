package org.springframework.boot.mytest.controller;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * @author linzherong
 * @date 2026/3/29 18:23
 */
@Service
public class MyTestService {

	@Async
	public void asyncTest() {
		System.out.println("====>>>>"+Thread.currentThread().getName() + "<<<<====");
		System.out.println(1);
	}

}
