package org.springframework.boot.mytest.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.stereotype.Component;

/**
 * @author linzherong
 * @date 2026/3/16 11:24
 */
public class MyBean implements BeanFactoryAware {

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		System.out.println("调用 setBeanFactory" + beanFactory);
	}
}
