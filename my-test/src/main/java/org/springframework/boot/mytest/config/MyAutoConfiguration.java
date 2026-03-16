package org.springframework.boot.mytest.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Import;

/**
 * @author linzherong
 * @date 2026/3/16 11:20
 */
@Import({MyAutoImportSelector1.class, MyAutoImportSelector2.class})
public class MyAutoConfiguration {
}
