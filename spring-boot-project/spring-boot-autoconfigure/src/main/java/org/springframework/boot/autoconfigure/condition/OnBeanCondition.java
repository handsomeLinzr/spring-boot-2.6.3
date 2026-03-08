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

package org.springframework.boot.autoconfigure.condition;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.HierarchicalBeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.boot.autoconfigure.condition.ConditionMessage.Style;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotation.Adapt;
import org.springframework.core.annotation.MergedAnnotationCollectors;
import org.springframework.core.annotation.MergedAnnotationPredicates;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

// Bean 存在和不存在的情况下的匹配规则
// 最低优先级
/**
 * {@link Condition} that checks for the presence or absence of specific beans.
 *
 * @author Phillip Webb
 * @author Dave Syer
 * @author Jakub Kubrynski
 * @author Stephane Nicoll
 * @author Andy Wilkinson
 * @see ConditionalOnBean
 * @see ConditionalOnMissingBean
 * @see ConditionalOnSingleCandidate
 */
@Order(Ordered.LOWEST_PRECEDENCE)
class OnBeanCondition extends FilteringSpringBootCondition implements ConfigurationCondition {

	@Override
	public ConfigurationPhase getConfigurationPhase() {
		return ConfigurationPhase.REGISTER_BEAN;
	}

	@Override
	protected final ConditionOutcome[] getOutcomes(String[] autoConfigurationClasses,
			AutoConfigurationMetadata autoConfigurationMetadata) {
		ConditionOutcome[] outcomes = new ConditionOutcome[autoConfigurationClasses.length];
		for (int i = 0; i < outcomes.length; i++) {
			String autoConfigurationClass = autoConfigurationClasses[i];
			if (autoConfigurationClass != null) {
				Set<String> onBeanTypes = autoConfigurationMetadata.getSet(autoConfigurationClass, "ConditionalOnBean");
				outcomes[i] = getOutcome(onBeanTypes, ConditionalOnBean.class);
				if (outcomes[i] == null) {
					Set<String> onSingleCandidateTypes = autoConfigurationMetadata.getSet(autoConfigurationClass,
							"ConditionalOnSingleCandidate");
					outcomes[i] = getOutcome(onSingleCandidateTypes, ConditionalOnSingleCandidate.class);
				}
			}
		}
		return outcomes;
	}

	private ConditionOutcome getOutcome(Set<String> requiredBeanTypes, Class<? extends Annotation> annotation) {
		List<String> missing = filter(requiredBeanTypes, ClassNameFilter.MISSING, getBeanClassLoader());
		if (!missing.isEmpty()) {
			ConditionMessage message = ConditionMessage.forCondition(annotation)
					.didNotFind("required type", "required types").items(Style.QUOTE, missing);
			return ConditionOutcome.noMatch(message);
		}
		return null;
	}

	// 根据自己的匹配逻辑，获取匹配结果
	// 其实就是对 ConditionalOnBean、ConditionalOnSingleCandidate、ConditionalOnMissingBean
	// 这三个注解的 bean 进行解析和匹配，最后得到匹配结果
	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		// 先定义一个空的匹配信息
		ConditionMessage matchMessage = ConditionMessage.empty();
		// 获取这个元数据的注解
		MergedAnnotations annotations = metadata.getAnnotations();

		// 判断如果有注解 ConditionalOnBean
		if (annotations.isPresent(ConditionalOnBean.class)) {
			// 对注解 ConditionalOnBean 进行解析，最后得到注解属性配置
			Spec<ConditionalOnBean> spec = new Spec<>(context, metadata, annotations, ConditionalOnBean.class);
			// 通过匹配逻辑，获取匹配结果
			MatchResult matchResult = getMatchingBeans(context, spec);

			// 判断是否匹配结果不是都匹配上
			if (!matchResult.isAllMatched()) {
				// 封装匹配失败原因
				String reason = createOnBeanNoMatchReason(matchResult);
				// 返回匹配失败
				// ConditionalOnBean 类型要求必须全部匹配上，否则就是匹配失败
				return ConditionOutcome.noMatch(spec.message().because(reason));
			}
			// 如果全部匹配上，则记录匹配得到的所有 beanNames
			matchMessage = spec.message(matchMessage).found("bean", "beans").items(Style.QUOTE,
					matchResult.getNamesOfAllMatches());
		}

		// 判断如果有注解 ConditionalOnSingleCandidate
		if (metadata.isAnnotated(ConditionalOnSingleCandidate.class.getName())) {
			// 对注解 ConditionalOnBean 进行解析，最后得到注解属性配置
			Spec<ConditionalOnSingleCandidate> spec = new SingleCandidateSpec(context, metadata, annotations);
			// 通过匹配逻辑，获取匹配结果
			MatchResult matchResult = getMatchingBeans(context, spec);

			if (!matchResult.isAllMatched()) {
				// 如果没有全部匹配，则就是不匹配
				return ConditionOutcome.noMatch(spec.message().didNotFind("any beans").atAll());
			}
			// 如果全部匹配上了，但是当前能匹配上的 beanNames 不止 1 个
			else if (!hasSingleAutowireCandidate(context.getBeanFactory(), matchResult.getNamesOfAllMatches(),
					spec.getStrategy() == SearchStrategy.ALL)) {
				// 返回匹配失败
				return ConditionOutcome.noMatch(spec.message().didNotFind("a primary bean from beans")
						.items(Style.QUOTE, matchResult.getNamesOfAllMatches()));
			}
			// 匹配成功，则将匹配的 beanNames 添加到结果中
			matchMessage = spec.message(matchMessage).found("a primary bean from beans").items(Style.QUOTE,
					matchResult.getNamesOfAllMatches());
		}

		// 判断如果有注解 ConditionalOnMissingBean
		if (metadata.isAnnotated(ConditionalOnMissingBean.class.getName())) {
			// 解析注解
			Spec<ConditionalOnMissingBean> spec = new Spec<>(context, metadata, annotations,
					ConditionalOnMissingBean.class);
			// 获取匹配结果
			MatchResult matchResult = getMatchingBeans(context, spec);

			// 判断只有要有任一匹配上
			if (matchResult.isAnyMatched()) {
				// 创建匹配失败原因
				String reason = createOnMissingBeanNoMatchReason(matchResult);
				// 返回匹配失败的结果
				return ConditionOutcome.noMatch(spec.message().because(reason));
			}
			// 全部匹配不上，则添加记录
			matchMessage = spec.message(matchMessage).didNotFind("any beans").atAll();
		}
		// 返回最后的结果，返回匹配上了
		return ConditionOutcome.match(matchMessage);
	}

	/**
	 * 具体的匹配逻辑，根据注解的属性，然后通过 beanFactory 获取对应的 beanNames
	 * 再通过各自的类型和属性流程进行辨认处理，最后得到匹配后的结果，有匹配上、没匹配上的记录
	 */
	protected final MatchResult getMatchingBeans(ConditionContext context, Spec<?> spec) {
		// 获取类加载器
		ClassLoader classLoader = context.getClassLoader();
		// 获取 bean 工厂
		ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
		// 判断是否当前得到的寻找策略不是 CURRENT
		boolean considerHierarchy = spec.getStrategy() != SearchStrategy.CURRENT;
		// 获取 parameterizedContainers，没设置的默认 null
		Set<Class<?>> parameterizedContainers = spec.getParameterizedContainers();
		// 如果寻找策略是 ANCESTORS
		if (spec.getStrategy() == SearchStrategy.ANCESTORS) {
			// 获取父的 bean 工厂
			BeanFactory parent = beanFactory.getParentBeanFactory();
			Assert.isInstanceOf(ConfigurableListableBeanFactory.class, parent,
					"Unable to use SearchStrategy.ANCESTORS");
			// 将 父的 beanFactory 强转成 ConfigurableListableBeanFactory，赋值给 beanFactory
			beanFactory = (ConfigurableListableBeanFactory) parent;
		}
		// 新建匹配结果
		MatchResult result = new MatchResult();
		// 获取当前 beanFactory 中所有的属于 spec.getIgnoredTypes() 类型的 beanNames
		// 这些都是要被忽略的
		Set<String> beansIgnoredByType = getNamesOfBeansIgnoredByType(classLoader, beanFactory, considerHierarchy,
				spec.getIgnoredTypes(), parameterizedContainers);
		// 遍历 spec,types
		for (String type : spec.getTypes()) {
			// 获取当前 beanFactory 中的所有 type 类型的 beanNames
			Collection<String> typeMatches = getBeanNamesForType(classLoader, considerHierarchy, beanFactory, type,
					parameterizedContainers);
			// 遍历 typeMatches，也就是遍历所有 spec.getTypes 的类型
			Iterator<String> iterator = typeMatches.iterator();
			while (iterator.hasNext()) {
				// 当前的属性，type 或者 value，也就是对应的 bean 的名称
				String match = iterator.next();
				// 判断如果当前这个 type 在忽略的 bean 中时，或者是代理
				// 把 beansIgnoredByType 中存在的 beanName 从当前这里移除掉
				if (beansIgnoredByType.contains(match) || ScopedProxyUtils.isScopedTarget(match)) {
					iterator.remove();
				}
			}
			// 通过上边的移除，走到这里得到的所有 typeMatches 里的值，都是不在 beansIgnoredByType 中
			if (typeMatches.isEmpty()) {
				// 如果最后得到的 typeMatches 是空的，也就是当前这个 type 没有匹配的 beanNames 了，则说明没有匹配上
				// 记录到 result 的 unmatchedTypes 中
				result.recordUnmatchedType(type);
			}
			else {
				// 否指则，将剩余的匹配上的 typeMatches 记录到 result 的 matchedTypes 中和 namesOfAllMatches 中
				result.recordMatchedType(type, typeMatches);
			}
		}

		// 遍历当前注解中的 annotations 配置的属性
		for (String annotation : spec.getAnnotations()) {
			// 根据 annotation 获取所有的当前 beanFactory 中有这个注解的 beanNames
			Set<String> annotationMatches = getBeanNamesForAnnotation(classLoader, beanFactory, annotation,
					considerHierarchy);
			// 将所有收集到的 beanNames 中，移除掉所有 beansIgnoredByType 中存在的 beanNames
			annotationMatches.removeAll(beansIgnoredByType);
			if (annotationMatches.isEmpty()) {
				// 移除掉后，如果得到的匹配类型空了，则将当前这个注解类型添加到未匹配记录中
				result.recordUnmatchedAnnotation(annotation);
			}
			else {
				// 如果不为空，则将当前注解类型，和能匹配上的 beanNames 记录到匹配记录中
				result.recordMatchedAnnotation(annotation, annotationMatches);
			}
		}
		// 遍历所有的 names
		for (String beanName : spec.getNames()) {
			// 判断如果不在 beansIgnoredByType 中，且 beanFactory 存在，则添加到匹配记录上
			if (!beansIgnoredByType.contains(beanName) && containsBean(beanFactory, beanName, considerHierarchy)) {
				result.recordMatchedName(beanName);
			}
			else {
				// 否则，添加到不匹配记录中
				result.recordUnmatchedName(beanName);
			}
		}
		// 返回匹配结果 result
		return result;
	}

	/**
	 * 从 beanFactory 中收集所有属于 ignoredTypes 里的类型的 beanNames
	 * 如果 considerHierarchy 是 true，且 beanFactory 属于 HierarchicalBeanFactory，则继续向父容器递归获取
	 */
	private Set<String> getNamesOfBeansIgnoredByType(ClassLoader classLoader, ListableBeanFactory beanFactory,
			boolean considerHierarchy, Set<String> ignoredTypes, Set<Class<?>> parameterizedContainers) {
		Set<String> result = null;
		// 遍历所有忽略的类型
		for (String ignoredType : ignoredTypes) {
			// 获取当前 beanFactory 中的所有 ignoredType 类型的 beanNames
			Collection<String> ignoredNames = getBeanNamesForType(classLoader, considerHierarchy, beanFactory,
					ignoredType, parameterizedContainers);
			// 收集添加到 result 中
			result = addAll(result, ignoredNames);
		}
		// 返回 result
		return (result != null) ? result : Collections.emptySet();
	}

	/**
	 * 获取 beanFactory 中所有的 type 类型的 beanNames
	 */
	private Set<String> getBeanNamesForType(ClassLoader classLoader, boolean considerHierarchy,
			ListableBeanFactory beanFactory, String type, Set<Class<?>> parameterizedContainers) throws LinkageError {
		try {
			// 返回 beanFactory 中所有的 type 类型的 beanNames
			// resolve(type, classLoader) 反射加载 type，加载成一个 Class 对象
			return getBeanNamesForType(beanFactory, considerHierarchy, resolve(type, classLoader),
					parameterizedContainers);
		}
		catch (ClassNotFoundException | NoClassDefFoundError ex) {
			return Collections.emptySet();
		}
	}

	/**
	 * 从 beanFactory 中获取所有类型是 type 的 beanNames 返回
	 */
	private Set<String> getBeanNamesForType(ListableBeanFactory beanFactory, boolean considerHierarchy, Class<?> type,
			Set<Class<?>> parameterizedContainers) {
		// 从 beanFactory 中收集所有的类型为 type 的 beanNames，收集到 result 中返回
		Set<String> result = collectBeanNamesForType(beanFactory, considerHierarchy, type, parameterizedContainers,
				null);
		// 返回 result，如果是空的则返回空的 set 集合
		return (result != null) ? result : Collections.emptySet();
	}

	/**
	 * 从 beanFactory 中获取所有的 type 类型的 beanNames，进行收集
	 * 如果 considerHierarchy = true，且当前 beanFactory 属于 HierarchicalBeanFactory，则继续递归从父容器中收集
	 * 最后返回收集到的所有 beanNames
	 */
	private Set<String> collectBeanNamesForType(ListableBeanFactory beanFactory, boolean considerHierarchy,
			Class<?> type, Set<Class<?>> parameterizedContainers, Set<String> result) {
		// getBeanNamesForType 获取当前 beanFactory 中 type 类型的 beanNames
		// 添加所有 type 类型的 beanName 到 result 中
		result = addAll(result, beanFactory.getBeanNamesForType(type, true, false));

		// 遍历 parameterizedContainers 处理类型
		for (Class<?> container : parameterizedContainers) {
			ResolvableType generic = ResolvableType.forClassWithGenerics(container, type);
			result = addAll(result, beanFactory.getBeanNamesForType(generic, true, false));
		}

		// 判断如果 beanFactory 属于 HierarchicalBeanFactory 类型，且当前传入的 considerHierarchy 是 true
		if (considerHierarchy && beanFactory instanceof HierarchicalBeanFactory) {
			// 获取父工厂
			BeanFactory parent = ((HierarchicalBeanFactory) beanFactory).getParentBeanFactory();
			// 判断如果父工厂是 ListableBeanFactory 类型
			if (parent instanceof ListableBeanFactory) {
				// 继续递归处理，收集所有 type 类型的 beanNames 到 result 中
				result = collectBeanNamesForType((ListableBeanFactory) parent, considerHierarchy, type,
						parameterizedContainers, result);
			}
		}
		// 对收集到的所有 type 类型的 beanNames 返回
		return result;
	}

	/**
	 * 获取 beanFactory 中所有加了注解 type 的 beanNames，并返回
	 */
	private Set<String> getBeanNamesForAnnotation(ClassLoader classLoader, ConfigurableListableBeanFactory beanFactory,
			String type, boolean considerHierarchy) throws LinkageError {
		Set<String> result = null;
		try {
			// 获取所有的有 type 注解的 beanNames 并返回
			result = collectBeanNamesForAnnotation(beanFactory, resolveAnnotationType(classLoader, type),
					considerHierarchy, result);
		}
		catch (ClassNotFoundException ex) {
			// Continue
		}
		return (result != null) ? result : Collections.emptySet();
	}

	@SuppressWarnings("unchecked")
	private Class<? extends Annotation> resolveAnnotationType(ClassLoader classLoader, String type)
			throws ClassNotFoundException {
		return (Class<? extends Annotation>) resolve(type, classLoader);
	}

	/**
	 * 获取 beanFactory 中的所有加了注解 annotationType 的 beanName，返回
	 */
	private Set<String> collectBeanNamesForAnnotation(ListableBeanFactory beanFactory,
			Class<? extends Annotation> annotationType, boolean considerHierarchy, Set<String> result) {
		// 从 beanFactory 中获取有 annotationType 这个注解的所有的 beanNames，添加到 result 中
		result = addAll(result, beanFactory.getBeanNamesForAnnotation(annotationType));
		if (considerHierarchy) {
			// 如果没有限制只能从当前层级获取
			// 则获取父工厂，继续递归获取
			BeanFactory parent = ((HierarchicalBeanFactory) beanFactory).getParentBeanFactory();
			if (parent instanceof ListableBeanFactory) {
				result = collectBeanNamesForAnnotation((ListableBeanFactory) parent, annotationType, considerHierarchy,
						result);
			}
		}
		return result;
	}

	private boolean containsBean(ConfigurableListableBeanFactory beanFactory, String beanName,
			boolean considerHierarchy) {
		if (considerHierarchy) {
			return beanFactory.containsBean(beanName);
		}
		return beanFactory.containsLocalBean(beanName);
	}

	// 根据 matchResult 匹配结果，创建匹配失败原因
	private String createOnBeanNoMatchReason(MatchResult matchResult) {
		StringBuilder reason = new StringBuilder();
		appendMessageForNoMatches(reason, matchResult.getUnmatchedAnnotations(), "annotated with");
		appendMessageForNoMatches(reason, matchResult.getUnmatchedTypes(), "of type");
		appendMessageForNoMatches(reason, matchResult.getUnmatchedNames(), "named");
		return reason.toString();
	}

	private void appendMessageForNoMatches(StringBuilder reason, Collection<String> unmatched, String description) {
		if (!unmatched.isEmpty()) {
			if (reason.length() > 0) {
				reason.append(" and ");
			}
			reason.append("did not find any beans ");
			reason.append(description);
			reason.append(" ");
			reason.append(StringUtils.collectionToDelimitedString(unmatched, ", "));
		}
	}

	private String createOnMissingBeanNoMatchReason(MatchResult matchResult) {
		StringBuilder reason = new StringBuilder();
		appendMessageForMatches(reason, matchResult.getMatchedAnnotations(), "annotated with");
		appendMessageForMatches(reason, matchResult.getMatchedTypes(), "of type");
		if (!matchResult.getMatchedNames().isEmpty()) {
			if (reason.length() > 0) {
				reason.append(" and ");
			}
			reason.append("found beans named ");
			reason.append(StringUtils.collectionToDelimitedString(matchResult.getMatchedNames(), ", "));
		}
		return reason.toString();
	}

	private void appendMessageForMatches(StringBuilder reason, Map<String, Collection<String>> matches,
			String description) {
		if (!matches.isEmpty()) {
			matches.forEach((key, value) -> {
				if (reason.length() > 0) {
					reason.append(" and ");
				}
				reason.append("found beans ");
				reason.append(description);
				reason.append(" '");
				reason.append(key);
				reason.append("' ");
				reason.append(StringUtils.collectionToDelimitedString(value, ", "));
			});
		}
	}

	private boolean hasSingleAutowireCandidate(ConfigurableListableBeanFactory beanFactory, Set<String> beanNames,
			boolean considerHierarchy) {
		// beanNames 只有 1 个
		// 或者有优先标识的 beanNames 只有 1 个
		// 则返回 true
		return (beanNames.size() == 1 || getPrimaryBeans(beanFactory, beanNames, considerHierarchy).size() == 1);
	}

	/**
	 * 获取 beanNames 中所有中，优先的 bean
	 */
	private List<String> getPrimaryBeans(ConfigurableListableBeanFactory beanFactory, Set<String> beanNames,
			boolean considerHierarchy) {
		// 定义一个list
		List<String> primaryBeans = new ArrayList<>();
		// 遍历所有的 beanName
		for (String beanName : beanNames) {
			// 获取 bd
			BeanDefinition beanDefinition = findBeanDefinition(beanFactory, beanName, considerHierarchy);
			// 如果 bd 不为空，且是优先的这，则添加到 primaryBeans 中
			if (beanDefinition != null && beanDefinition.isPrimary()) {
				primaryBeans.add(beanName);
			}
		}
		// 返回这个得到的优先 bean
		return primaryBeans;
	}

	private BeanDefinition findBeanDefinition(ConfigurableListableBeanFactory beanFactory, String beanName,
			boolean considerHierarchy) {
		if (beanFactory.containsBeanDefinition(beanName)) {
			return beanFactory.getBeanDefinition(beanName);
		}
		if (considerHierarchy && beanFactory.getParentBeanFactory() instanceof ConfigurableListableBeanFactory) {
			return findBeanDefinition(((ConfigurableListableBeanFactory) beanFactory.getParentBeanFactory()), beanName,
					considerHierarchy);
		}
		return null;
	}

	private static Set<String> addAll(Set<String> result, Collection<String> additional) {
		if (CollectionUtils.isEmpty(additional)) {
			return result;
		}
		result = (result != null) ? result : new LinkedHashSet<>();
		result.addAll(additional);
		return result;
	}

	// 添加所有的 additional 到 result 中，并返回 result
	private static Set<String> addAll(Set<String> result, String[] additional) {
		// 如果 additional 是空的，返回 result
		if (ObjectUtils.isEmpty(additional)) {
			return result;
		}
		// 创建 result
		result = (result != null) ? result : new LinkedHashSet<>();
		// 添加所有 additional 到 result
		Collections.addAll(result, additional);
		// 返回 result
		return result;
	}

	/**
	 * A search specification extracted from the underlying annotation.
	 */
	private static class Spec<A extends Annotation> {

		private final ClassLoader classLoader;

		private final Class<? extends Annotation> annotationType;

		// 属性
		private final Set<String> names;

		// bean 方法的返回类型
		private final Set<String> types;

		private final Set<String> annotations;

		// 属性
		private final Set<String> ignoredTypes;

		private final Set<Class<?>> parameterizedContainers;

		// 查找策略
		private final SearchStrategy strategy;

		/**
		 * 根据给定的 annotations 注解中，获取特定的 annotationType 注解
		 * @param context
		 * @param metadata
		 * @param annotations
		 * @param annotationType
		 */
		Spec(ConditionContext context, AnnotatedTypeMetadata metadata, MergedAnnotations annotations,
				Class<A> annotationType) {
			// 从 annotations 中获取所有的 annotationType 类型的注解，解析对应的属性，封装成 MultiValueMap
			MultiValueMap<String, Object> attributes = annotations.stream(annotationType)
					.filter(MergedAnnotationPredicates.unique(MergedAnnotation::getMetaTypes))
					.collect(MergedAnnotationCollectors.toMultiValueMap(Adapt.CLASS_TO_STRING));
			// annotations 中获取到 annotationType 类型的注解
			MergedAnnotation<A> annotation = annotations.get(annotationType);

			// 获取 classLoader
			this.classLoader = context.getClassLoader();
			// 注解类型
			this.annotationType = annotationType;
			// 从 attributes 中获取对应的属性，设置到当前对象中
			this.names = extract(attributes, "name");
			this.annotations = extract(attributes, "annotation");
			this.ignoredTypes = extract(attributes, "ignored", "ignoredType");
			this.parameterizedContainers = resolveWhenPossible(extract(attributes, "parameterizedContainer"));
			this.strategy = annotation.getValue("search", SearchStrategy.class).orElse(null);
			// 获取到 value 和 type 属性配置
			Set<String> types = extractTypes(attributes);
			BeanTypeDeductionException deductionException = null;
			if (types.isEmpty() && this.names.isEmpty()) {
				try {
					// 如果 types 是空的，且 names 也是空的
					// 也就是属性 value、type 和 name 都是没有值
					// 调用 deducedBeanType 后赋值给 types，即这里得到当前 bean 方法的返回类型
					types = deducedBeanType(context, metadata);
				}
				catch (BeanTypeDeductionException ex) {
					// 记录异常信息，赋值给 deductionException
					deductionException = ex;
				}
			}
			// 设置 types 属性
			this.types = types;
			// 校验异常
			validate(deductionException);
		}

		protected Set<String> extractTypes(MultiValueMap<String, Object> attributes) {
			return extract(attributes, "value", "type");
		}

		private Set<String> extract(MultiValueMap<String, Object> attributes, String... attributeNames) {
			if (attributes.isEmpty()) {
				return Collections.emptySet();
			}
			Set<String> result = new LinkedHashSet<>();
			for (String attributeName : attributeNames) {
				List<Object> values = attributes.getOrDefault(attributeName, Collections.emptyList());
				for (Object value : values) {
					if (value instanceof String[]) {
						merge(result, (String[]) value);
					}
					else if (value instanceof String) {
						merge(result, (String) value);
					}
				}
			}
			return result.isEmpty() ? Collections.emptySet() : result;
		}

		private void merge(Set<String> result, String... additional) {
			Collections.addAll(result, additional);
		}

		private Set<Class<?>> resolveWhenPossible(Set<String> classNames) {
			if (classNames.isEmpty()) {
				return Collections.emptySet();
			}
			Set<Class<?>> resolved = new LinkedHashSet<>(classNames.size());
			for (String className : classNames) {
				try {
					resolved.add(resolve(className, this.classLoader));
				}
				catch (ClassNotFoundException | NoClassDefFoundError ex) {
				}
			}
			return resolved;
		}

		// 判断是否满足 types、name、annotations 至少有一个
		protected void validate(BeanTypeDeductionException ex) {
			// 如果 types、name、annotations 都没有
			if (!hasAtLeastOneElement(this.types, this.names, this.annotations)) {
				// 构建 message
				String message = getAnnotationName() + " did not specify a bean using type, name or annotation";
				if (ex == null) {
					// 如果没有 ex 异常，则抛出 IllegalStateException
					throw new IllegalStateException(message);
				}
				// 否则抛出 IllegalStateException
				throw new IllegalStateException(message + " and the attempt to deduce the bean's type failed", ex);
			}
		}

		private boolean hasAtLeastOneElement(Set<?>... sets) {
			for (Set<?> set : sets) {
				if (!set.isEmpty()) {
					return true;
				}
			}
			return false;
		}

		protected final String getAnnotationName() {
			return "@" + ClassUtils.getShortName(this.annotationType);
		}

		// 推断 bean 的类型
		private Set<String> deducedBeanType(ConditionContext context, AnnotatedTypeMetadata metadata) {
			// 判断当前的这个 bean 属于方法上的，且有注解 Bean
			if (metadata instanceof MethodMetadata && metadata.isAnnotated(Bean.class.getName())) {
				// 调用 deducedBeanTypeForBeanMethod 最后得到方法的返回类型的 Class
				return deducedBeanTypeForBeanMethod(context, (MethodMetadata) metadata);
			}
			// 否则，返回空数组
			return Collections.emptySet();
		}

		// 推断方法 bean 的类型
		private Set<String> deducedBeanTypeForBeanMethod(ConditionContext context, MethodMetadata metadata) {
			try {
				// 获取当前这个 bean 方法的返回类型
				Class<?> returnType = getReturnType(context, metadata);
				// 返回这个类型
				return Collections.singleton(returnType.getName());
			}
			catch (Throwable ex) {
				// 异常
				throw new BeanTypeDeductionException(metadata.getDeclaringClassName(), metadata.getMethodName(), ex);
			}
		}

		// 获取 metadata 对应方法的返回类型
		private Class<?> getReturnType(ConditionContext context, MethodMetadata metadata)
				throws ClassNotFoundException, LinkageError {
			// 获取当前 context 的类加载器
			// Safe to load at this point since we are in the REGISTER_BEAN phase
			ClassLoader classLoader = context.getClassLoader();
			// metadata.getReturnTypeName() 得到方法的返回类型
			// resolve 通过 className 反射得到对应的 Class 对象
			// 所以这里得到的是方法返回的 Class 类型
			Class<?> returnType = resolve(metadata.getReturnTypeName(), classLoader);
			// 处理类型
			if (isParameterizedContainer(returnType)) {
				returnType = getReturnTypeGeneric(metadata, classLoader);
			}
			// 返回这个 Class 类型
			return returnType;
		}

		private boolean isParameterizedContainer(Class<?> type) {
			for (Class<?> parameterizedContainer : this.parameterizedContainers) {
				if (parameterizedContainer.isAssignableFrom(type)) {
					return true;
				}
			}
			return false;
		}

		private Class<?> getReturnTypeGeneric(MethodMetadata metadata, ClassLoader classLoader)
				throws ClassNotFoundException, LinkageError {
			Class<?> declaringClass = resolve(metadata.getDeclaringClassName(), classLoader);
			Method beanMethod = findBeanMethod(declaringClass, metadata.getMethodName());
			return ResolvableType.forMethodReturnType(beanMethod).resolveGeneric();
		}

		private Method findBeanMethod(Class<?> declaringClass, String methodName) {
			Method method = ReflectionUtils.findMethod(declaringClass, methodName);
			if (isBeanMethod(method)) {
				return method;
			}
			Method[] candidates = ReflectionUtils.getAllDeclaredMethods(declaringClass);
			for (Method candidate : candidates) {
				if (candidate.getName().equals(methodName) && isBeanMethod(candidate)) {
					return candidate;
				}
			}
			throw new IllegalStateException("Unable to find bean method " + methodName);
		}

		private boolean isBeanMethod(Method method) {
			return method != null && MergedAnnotations.from(method, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
					.isPresent(Bean.class);
		}

		// 获取查找策略，如果没设置，则返回 ALL
		private SearchStrategy getStrategy() {
			return (this.strategy != null) ? this.strategy : SearchStrategy.ALL;
		}

		Set<String> getNames() {
			return this.names;
		}

		Set<String> getTypes() {
			return this.types;
		}

		Set<String> getAnnotations() {
			return this.annotations;
		}

		Set<String> getIgnoredTypes() {
			return this.ignoredTypes;
		}

		Set<Class<?>> getParameterizedContainers() {
			return this.parameterizedContainers;
		}

		ConditionMessage.Builder message() {
			return ConditionMessage.forCondition(this.annotationType, this);
		}

		ConditionMessage.Builder message(ConditionMessage message) {
			return message.andCondition(this.annotationType, this);
		}

		@Override
		public String toString() {
			boolean hasNames = !this.names.isEmpty();
			boolean hasTypes = !this.types.isEmpty();
			boolean hasIgnoredTypes = !this.ignoredTypes.isEmpty();
			StringBuilder string = new StringBuilder();
			string.append("(");
			if (hasNames) {
				string.append("names: ");
				string.append(StringUtils.collectionToCommaDelimitedString(this.names));
				string.append(hasTypes ? " " : "; ");
			}
			if (hasTypes) {
				string.append("types: ");
				string.append(StringUtils.collectionToCommaDelimitedString(this.types));
				string.append(hasIgnoredTypes ? " " : "; ");
			}
			if (hasIgnoredTypes) {
				string.append("ignored: ");
				string.append(StringUtils.collectionToCommaDelimitedString(this.ignoredTypes));
				string.append("; ");
			}
			string.append("SearchStrategy: ");
			string.append(this.strategy.toString().toLowerCase(Locale.ENGLISH));
			string.append(")");
			return string.toString();
		}

	}

	/**
	 * Specialized {@link Spec specification} for
	 * {@link ConditionalOnSingleCandidate @ConditionalOnSingleCandidate}.
	 */
	private static class SingleCandidateSpec extends Spec<ConditionalOnSingleCandidate> {

		private static final Collection<String> FILTERED_TYPES = Arrays.asList("", Object.class.getName());

		SingleCandidateSpec(ConditionContext context, AnnotatedTypeMetadata metadata, MergedAnnotations annotations) {
			super(context, metadata, annotations, ConditionalOnSingleCandidate.class);
		}

		@Override
		protected Set<String> extractTypes(MultiValueMap<String, Object> attributes) {
			Set<String> types = super.extractTypes(attributes);
			types.removeAll(FILTERED_TYPES);
			return types;
		}

		@Override
		protected void validate(BeanTypeDeductionException ex) {
			Assert.isTrue(getTypes().size() == 1,
					() -> getAnnotationName() + " annotations must specify only one type (got "
							+ StringUtils.collectionToCommaDelimitedString(getTypes()) + ")");
		}

	}

	/**
	 * Results collected during the condition evaluation.
	 */
	private static final class MatchResult {

		private final Map<String, Collection<String>> matchedAnnotations = new HashMap<>();

		private final List<String> matchedNames = new ArrayList<>();

		// 能匹配上的 type 和 对应的 beanNames
		private final Map<String, Collection<String>> matchedTypes = new HashMap<>();

		private final List<String> unmatchedAnnotations = new ArrayList<>();

		private final List<String> unmatchedNames = new ArrayList<>();

		// 不能匹配上的 type
		private final List<String> unmatchedTypes = new ArrayList<>();

		// 能匹配上的所有的 beanNames
		private final Set<String> namesOfAllMatches = new HashSet<>();

		private void recordMatchedName(String name) {
			this.matchedNames.add(name);
			this.namesOfAllMatches.add(name);
		}

		private void recordUnmatchedName(String name) {
			this.unmatchedNames.add(name);
		}

		private void recordMatchedAnnotation(String annotation, Collection<String> matchingNames) {
			this.matchedAnnotations.put(annotation, matchingNames);
			this.namesOfAllMatches.addAll(matchingNames);
		}

		private void recordUnmatchedAnnotation(String annotation) {
			this.unmatchedAnnotations.add(annotation);
		}

		private void recordMatchedType(String type, Collection<String> matchingNames) {
			this.matchedTypes.put(type, matchingNames);
			this.namesOfAllMatches.addAll(matchingNames);
		}

		private void recordUnmatchedType(String type) {
			this.unmatchedTypes.add(type);
		}

		/**
		 * 判断是否所有都能匹配上
		 */
		boolean isAllMatched() {
			// 所有都能匹配上 = 注解、名称、类型都没有匹配不上的
			return this.unmatchedAnnotations.isEmpty() && this.unmatchedNames.isEmpty()
					&& this.unmatchedTypes.isEmpty();
		}

		/**
		 * 判断是否任一匹配上
		 */
		boolean isAnyMatched() {
			// 返回 注解、name、type 是否有任一个不为空，这就是有任一一个匹配上
			return (!this.matchedAnnotations.isEmpty()) || (!this.matchedNames.isEmpty())
					|| (!this.matchedTypes.isEmpty());
		}

		Map<String, Collection<String>> getMatchedAnnotations() {
			return this.matchedAnnotations;
		}

		List<String> getMatchedNames() {
			return this.matchedNames;
		}

		Map<String, Collection<String>> getMatchedTypes() {
			return this.matchedTypes;
		}

		List<String> getUnmatchedAnnotations() {
			return this.unmatchedAnnotations;
		}

		List<String> getUnmatchedNames() {
			return this.unmatchedNames;
		}

		List<String> getUnmatchedTypes() {
			return this.unmatchedTypes;
		}

		Set<String> getNamesOfAllMatches() {
			return this.namesOfAllMatches;
		}

	}

	/**
	 * Exception thrown when the bean type cannot be deduced.
	 */
	static final class BeanTypeDeductionException extends RuntimeException {

		private BeanTypeDeductionException(String className, String beanMethodName, Throwable cause) {
			super("Failed to deduce bean type for " + className + "." + beanMethodName, cause);
		}

	}

}
