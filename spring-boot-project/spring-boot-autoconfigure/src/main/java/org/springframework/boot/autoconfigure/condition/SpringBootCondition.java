/*
 * Copyright 2012-2019 the original author or authors.
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

package org.springframework.boot.autoconfigure.condition;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.ClassMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Base of all {@link Condition} implementations used with Spring Boot. Provides sensible
 * logging to help the user diagnose what classes are loaded.
 *
 * @author Phillip Webb
 * @author Greg Turnquist
 * @since 1.0.0
 */
public abstract class SpringBootCondition implements Condition {

	// 日志打印
	private final Log logger = LogFactory.getLog(getClass());

	// 匹配逻辑
	// 也就是扩展 Condition，作为匹配的逻辑入口
	@Override
	public final boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		// 获取当前这个 bean 对应的类或者方法名称
		String classOrMethodName = getClassOrMethodName(metadata);
		try {
			// 调用 getMatchOutcome 方法，调用到具体的子类实现中，拿到自定义的条件匹配结果信息
			ConditionOutcome outcome = getMatchOutcome(context, metadata);
			// 日志输出追踪
			logOutcome(classOrMethodName, outcome);
			// 记录结果 outcome
			recordEvaluation(context, classOrMethodName, outcome);
			// 根据得到的 outcome 匹配，返回最终的合并匹配结果
			// 这里其实就是放回 outcome.match 属性
			return outcome.isMatch();
		}
		catch (NoClassDefFoundError ex) {
			throw new IllegalStateException("Could not evaluate condition on " + classOrMethodName + " due to "
					+ ex.getMessage() + " not found. Make sure your own configuration does not rely on "
					+ "that class. This can also happen if you are "
					+ "@ComponentScanning a springframework package (e.g. if you "
					+ "put a @ComponentScan in the default package by mistake)", ex);
		}
		catch (RuntimeException ex) {
			throw new IllegalStateException("Error processing condition on " + getName(metadata), ex);
		}
	}

	private String getName(AnnotatedTypeMetadata metadata) {
		if (metadata instanceof AnnotationMetadata) {
			return ((AnnotationMetadata) metadata).getClassName();
		}
		if (metadata instanceof MethodMetadata) {
			MethodMetadata methodMetadata = (MethodMetadata) metadata;
			return methodMetadata.getDeclaringClassName() + "." + methodMetadata.getMethodName();
		}
		return metadata.toString();
	}

	/**
	 * 获取当前的 metadata 中，class 名称或者 method 名称
	 * @param metadata
	 * @return
	 */
	private static String getClassOrMethodName(AnnotatedTypeMetadata metadata) {
		// 判断如果当前是在类上
		if (metadata instanceof ClassMetadata) {
			// 返回对应的类名
			ClassMetadata classMetadata = (ClassMetadata) metadata;
			return classMetadata.getClassName();
		}
		// 当前是在方法上，返回当前的类名称+"#"+方法名
		MethodMetadata methodMetadata = (MethodMetadata) metadata;
		return methodMetadata.getDeclaringClassName() + "#" + methodMetadata.getMethodName();
	}

	protected final void logOutcome(String classOrMethodName, ConditionOutcome outcome) {
		// 判断是否开启了日志追踪，有则进行日志的追踪记录
		if (this.logger.isTraceEnabled()) {
			this.logger.trace(getLogMessage(classOrMethodName, outcome));
		}
	}

	/**
	 * 转化为日志记录的格式
	 * @param classOrMethodName
	 * @param outcome
	 * @return
	 */
	private StringBuilder getLogMessage(String classOrMethodName, ConditionOutcome outcome) {
		StringBuilder message = new StringBuilder();
		message.append("Condition ");
		message.append(ClassUtils.getShortName(getClass()));
		message.append(" on ");
		message.append(classOrMethodName);
		message.append(outcome.isMatch() ? " matched" : " did not match");
		if (StringUtils.hasLength(outcome.getMessage())) {
			message.append(" due to ");
			message.append(outcome.getMessage());
		}
		return message;
	}

	// 记录当前这个 condition 中匹配的结果
	private void recordEvaluation(ConditionContext context, String classOrMethodName, ConditionOutcome outcome) {
		// 判断是否 ConditionContext 中有 beanFactory 对象
		// 默认一般是有的
		if (context.getBeanFactory() != null) {
			// 从 beanFactory 中获取到 autoConfigurationReport 这个 bean
			// 调用 recordConditionEvaluation，将当前的匹配结果 outcome 记录添加到 this 中
			ConditionEvaluationReport.get(context.getBeanFactory()).recordConditionEvaluation(classOrMethodName, this,
					outcome);
		}
	}

	// 确认匹配结果
	/**
	 * Determine the outcome of the match along with suitable log output.
	 * @param context the condition context
	 * @param metadata the annotation metadata
	 * @return the condition outcome
	 */
	public abstract ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata);

	// 任意匹配
	/**
	 * Return true if any of the specified conditions match.
	 * @param context the context
	 * @param metadata the annotation meta-data
	 * @param conditions conditions to test
	 * @return {@code true} if any condition matches.
	 */
	protected final boolean anyMatches(ConditionContext context, AnnotatedTypeMetadata metadata,
			Condition... conditions) {
		// 获取所有的 conditions 条件表达
		for (Condition condition : conditions) {
			// 只要有一个匹配上返回 true，则直接返回 true 匹配上
			if (matches(context, metadata, condition)) {
				return true;
			}
		}
		return false;
	}

	// 匹配
	/**
	 * Return true if any of the specified condition matches.
	 * @param context the context
	 * @param metadata the annotation meta-data
	 * @param condition condition to test
	 * @return {@code true} if the condition matches.
	 */
	protected final boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata, Condition condition) {
		if (condition instanceof SpringBootCondition) {
			// 判断如果 condition 属于 SpringBootCondition
			// 则强转成 SpringBootCondition，调用 getMatchOutcome 得到匹配结果，返回匹配结果
			return ((SpringBootCondition) condition).getMatchOutcome(context, metadata).isMatch();
		}
		// 否则，非 SpringBootCondition，直接调用 matches 方法走匹配逻辑
		return condition.matches(context, metadata);
	}

}
