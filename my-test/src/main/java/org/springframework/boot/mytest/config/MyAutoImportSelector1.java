package org.springframework.boot.mytest.config;

import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotationMetadata;

/**
 * @author linzherong
 * @date 2026/3/16 11:21
 */
public class MyAutoImportSelector1 implements ImportSelector {
	@Override
	public String[] selectImports(AnnotationMetadata importingClassMetadata) {
		return new String[]{MyBean.class.getName()};
	}
}
