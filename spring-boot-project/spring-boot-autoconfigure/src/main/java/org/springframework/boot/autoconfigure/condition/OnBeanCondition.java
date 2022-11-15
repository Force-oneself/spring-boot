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
				// @ConditionalOnBean的处理
				Set<String> onBeanTypes = autoConfigurationMetadata.getSet(autoConfigurationClass, "ConditionalOnBean");
				outcomes[i] = getOutcome(onBeanTypes, ConditionalOnBean.class);
				if (outcomes[i] == null) {
					// @ConditionalOnSingleCandidate的处理
					Set<String> onSingleCandidateTypes = autoConfigurationMetadata.getSet(autoConfigurationClass,
							"ConditionalOnSingleCandidate");
					outcomes[i] = getOutcome(onSingleCandidateTypes, ConditionalOnSingleCandidate.class);
				}
			}
		}
		return outcomes;
	}

	/**
	 * 校验Bean的Class文件是不是存在
	 *
	 * @param requiredBeanTypes Bean Class
	 * @param annotation annotation
	 * @return  /
	 */
	private ConditionOutcome getOutcome(Set<String> requiredBeanTypes, Class<? extends Annotation> annotation) {
		List<String> missing = filter(requiredBeanTypes, ClassNameFilter.MISSING, getBeanClassLoader());
		if (!missing.isEmpty()) {
			ConditionMessage message = ConditionMessage.forCondition(annotation)
					.didNotFind("required type", "required types").items(Style.QUOTE, missing);
			return ConditionOutcome.noMatch(message);
		}
		return null;
	}

	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		ConditionMessage matchMessage = ConditionMessage.empty();
		MergedAnnotations annotations = metadata.getAnnotations();
		if (annotations.isPresent(ConditionalOnBean.class)) {
			Spec<ConditionalOnBean> spec = new Spec<>(context, metadata, annotations, ConditionalOnBean.class);
			MatchResult matchResult = getMatchingBeans(context, spec);
			if (!matchResult.isAllMatched()) {
				String reason = createOnBeanNoMatchReason(matchResult);
				return ConditionOutcome.noMatch(spec.message().because(reason));
			}
			matchMessage = spec.message(matchMessage).found("bean", "beans").items(Style.QUOTE,
					matchResult.getNamesOfAllMatches());
		}
		if (metadata.isAnnotated(ConditionalOnSingleCandidate.class.getName())) {
			Spec<ConditionalOnSingleCandidate> spec = new SingleCandidateSpec(context, metadata, annotations);
			MatchResult matchResult = getMatchingBeans(context, spec);
			if (!matchResult.isAllMatched()) {
				return ConditionOutcome.noMatch(spec.message().didNotFind("any beans").atAll());
			}
			else if (!hasSingleAutowireCandidate(context.getBeanFactory(), matchResult.getNamesOfAllMatches(),
					spec.getStrategy() == SearchStrategy.ALL)) {
				return ConditionOutcome.noMatch(spec.message().didNotFind("a primary bean from beans")
						.items(Style.QUOTE, matchResult.getNamesOfAllMatches()));
			}
			matchMessage = spec.message(matchMessage).found("a primary bean from beans").items(Style.QUOTE,
					matchResult.getNamesOfAllMatches());
		}
		if (metadata.isAnnotated(ConditionalOnMissingBean.class.getName())) {
			Spec<ConditionalOnMissingBean> spec = new Spec<>(context, metadata, annotations,
					ConditionalOnMissingBean.class);
			MatchResult matchResult = getMatchingBeans(context, spec);
			if (matchResult.isAnyMatched()) {
				String reason = createOnMissingBeanNoMatchReason(matchResult);
				return ConditionOutcome.noMatch(spec.message().because(reason));
			}
			matchMessage = spec.message(matchMessage).didNotFind("any beans").atAll();
		}
		return ConditionOutcome.match(matchMessage);
	}

	/**
	 * 获取封装后的匹配结果
	 *
	 * @param context context
	 * @param spec    @Condition注解相应的参数封装类
	 * @return /
	 */
	protected final MatchResult getMatchingBeans(ConditionContext context, Spec<?> spec) {
		ClassLoader classLoader = context.getClassLoader();
		ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
		boolean considerHierarchy = spec.getStrategy() != SearchStrategy.CURRENT;
		Set<Class<?>> parameterizedContainers = spec.getParameterizedContainers();
		if (spec.getStrategy() == SearchStrategy.ANCESTORS) {
			BeanFactory parent = beanFactory.getParentBeanFactory();
			Assert.isInstanceOf(ConfigurableListableBeanFactory.class, parent,
					"Unable to use SearchStrategy.ANCESTORS");
			beanFactory = (ConfigurableListableBeanFactory) parent;
		}
		MatchResult result = new MatchResult();
		// 获取根据类型忽略的Bean
		Set<String> beansIgnoredByType = getNamesOfBeansIgnoredByType(classLoader, beanFactory, considerHierarchy,
				spec.getIgnoredTypes(), parameterizedContainers);
		for (String type : spec.getTypes()) {
			// 获取该类型下匹配的Bean名
			Collection<String> typeMatches = getBeanNamesForType(classLoader, considerHierarchy, beanFactory, type,
					parameterizedContainers);
			Iterator<String> iterator = typeMatches.iterator();
			while (iterator.hasNext()) {
				String match = iterator.next();
				// 删除需要忽略的类型，以及作用域无用的Bean
				if (beansIgnoredByType.contains(match) || ScopedProxyUtils.isScopedTarget(match)) {
					iterator.remove();
				}
			}
			// 记录该类型是否匹配
			if (typeMatches.isEmpty()) {
				result.recordUnmatchedType(type);
			} else {
				result.recordMatchedType(type, typeMatches);
			}
		}
		for (String annotation : spec.getAnnotations()) {
			// 获取该注解下匹配的Bean名
			Set<String> annotationMatches = getBeanNamesForAnnotation(classLoader, beanFactory, annotation,
					considerHierarchy);
			// 去掉需要忽略的
			annotationMatches.removeAll(beansIgnoredByType);
			// 记录有该注解的Bean是否匹配
			if (annotationMatches.isEmpty()) {
				result.recordUnmatchedAnnotation(annotation);
			} else {
				result.recordMatchedAnnotation(annotation, annotationMatches);
			}
		}
		for (String beanName : spec.getNames()) {
			if (!beansIgnoredByType.contains(beanName) && containsBean(beanFactory, beanName, considerHierarchy)) {
				// 没有被忽略并且在BeanFactory中存在的Bean
				result.recordMatchedName(beanName);
			} else {
				result.recordUnmatchedName(beanName);
			}
		}
		return result;
	}

	/**
	 * 根据类型获取忽略的Bean名称集合
	 *
	 * @param classLoader             类加载器
	 * @param beanFactory             BeanFactory
	 * @param considerHierarchy       层次结构
	 * @param ignoredTypes            忽略的类型
	 * @param parameterizedContainers 包含的范型类型
	 * @return Bean名称
	 */
	private Set<String> getNamesOfBeansIgnoredByType(ClassLoader classLoader, ListableBeanFactory beanFactory,
			boolean considerHierarchy, Set<String> ignoredTypes, Set<Class<?>> parameterizedContainers) {
		Set<String> result = null;
		for (String ignoredType : ignoredTypes) {
			Collection<String> ignoredNames = getBeanNamesForType(classLoader, considerHierarchy, beanFactory,
					ignoredType, parameterizedContainers);
			result = addAll(result, ignoredNames);
		}
		return (result != null) ? result : Collections.emptySet();
	}

	/**
	 * 根据类型获取的Bean名称集合
	 *
	 * @param classLoader             类加载器
	 * @param beanFactory             BeanFactory
	 * @param considerHierarchy       层次结构
	 * @param type                    忽略的类型
	 * @param parameterizedContainers 包含的范型类型
	 * @return Bean名称
	 */
	private Set<String> getBeanNamesForType(ClassLoader classLoader, boolean considerHierarchy,
			ListableBeanFactory beanFactory, String type, Set<Class<?>> parameterizedContainers) throws LinkageError {
		try {
			return getBeanNamesForType(beanFactory, considerHierarchy, resolve(type, classLoader),
					parameterizedContainers);
		}
		catch (ClassNotFoundException | NoClassDefFoundError ex) {
			return Collections.emptySet();
		}
	}

	/**
	 * 根据类型获取Bean名称集合
	 *
	 * @param beanFactory             BeanFactory
	 * @param considerHierarchy       层次结构
	 * @param type                    Bean的Class
	 * @param parameterizedContainers 包含的范型类型
	 * @return /
	 */
	private Set<String> getBeanNamesForType(ListableBeanFactory beanFactory, boolean considerHierarchy, Class<?> type,
			Set<Class<?>> parameterizedContainers) {
		Set<String> result = collectBeanNamesForType(beanFactory, considerHierarchy, type, parameterizedContainers,
				null);
		return (result != null) ? result : Collections.emptySet();
	}

	/**
	 * 根据类型收集Bean名称集合
	 *
	 * @param beanFactory             BeanFactory
	 * @param considerHierarchy       层次结构
	 * @param type                    Bean的Class
	 * @param parameterizedContainers 包含的范型类型
	 * @param result                  result
	 * @return /
	 */
	private Set<String> collectBeanNamesForType(ListableBeanFactory beanFactory, boolean considerHierarchy,
			Class<?> type, Set<Class<?>> parameterizedContainers, Set<String> result) {
		// 通过BeanFactory获取到名字
		result = addAll(result, beanFactory.getBeanNamesForType(type, true, false));
		// 范型处理
		for (Class<?> container : parameterizedContainers) {
			ResolvableType generic = ResolvableType.forClassWithGenerics(container, type);
			result = addAll(result, beanFactory.getBeanNamesForType(generic, true, false));
		}
		// 父容器拿一遍
		if (considerHierarchy && beanFactory instanceof HierarchicalBeanFactory) {
			BeanFactory parent = ((HierarchicalBeanFactory) beanFactory).getParentBeanFactory();
			if (parent instanceof ListableBeanFactory) {
				// 递归来一下
				result = collectBeanNamesForType((ListableBeanFactory) parent, considerHierarchy, type,
						parameterizedContainers, result);
			}
		}
		return result;
	}

	/**
	 * 根据注解获取Bean的名称集合
	 *
	 * @param classLoader       类加载器
	 * @param beanFactory       BeanFactory
	 * @param type              Bean Class
	 * @param considerHierarchy 查询层级
	 * @return /
	 */
	private Set<String> getBeanNamesForAnnotation(ClassLoader classLoader, ConfigurableListableBeanFactory beanFactory,
			String type, boolean considerHierarchy) throws LinkageError {
		Set<String> result = null;
		try {
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

	private Set<String> collectBeanNamesForAnnotation(ListableBeanFactory beanFactory,
			Class<? extends Annotation> annotationType, boolean considerHierarchy, Set<String> result) {
		result = addAll(result, beanFactory.getBeanNamesForAnnotation(annotationType));
		if (considerHierarchy) {
			BeanFactory parent = ((HierarchicalBeanFactory) beanFactory).getParentBeanFactory();
			if (parent instanceof ListableBeanFactory) {
				// 一样的递归
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
		return (beanNames.size() == 1 || getPrimaryBeans(beanFactory, beanNames, considerHierarchy).size() == 1);
	}

	private List<String> getPrimaryBeans(ConfigurableListableBeanFactory beanFactory, Set<String> beanNames,
			boolean considerHierarchy) {
		List<String> primaryBeans = new ArrayList<>();
		for (String beanName : beanNames) {
			BeanDefinition beanDefinition = findBeanDefinition(beanFactory, beanName, considerHierarchy);
			if (beanDefinition != null && beanDefinition.isPrimary()) {
				primaryBeans.add(beanName);
			}
		}
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

	private static Set<String> addAll(Set<String> result, String[] additional) {
		if (ObjectUtils.isEmpty(additional)) {
			return result;
		}
		result = (result != null) ? result : new LinkedHashSet<>();
		Collections.addAll(result, additional);
		return result;
	}

	/**
	 * 从基础注释中提取的搜索规范。
	 */
	private static class Spec<A extends Annotation> {

		/**
		 * 条件筛选的类加载器
		 */
		private final ClassLoader classLoader;

		/**
		 * 当前Condition注解类型
		 */
		private final Class<? extends Annotation> annotationType;

		/**
		 * 需要存在或者不存在的类型，根据注解来判别
		 */
		private final Set<String> names;

		/**
		 * 需要存在或者不存在的类型，根据注解来判别
		 */
		private final Set<String> types;

		/**
		 * Bean上需要的注解
		 */
		private final Set<String> annotations;

		/**
		 * 需要忽略的类型
		 */
		private final Set<String> ignoredTypes;

		/**
		 * Bean的范型参数需要包含的类型
		 */
		private final Set<Class<?>> parameterizedContainers;

		/**
		 * 查询策略
		 */
		private final SearchStrategy strategy;

		Spec(ConditionContext context, AnnotatedTypeMetadata metadata, MergedAnnotations annotations,
				Class<A> annotationType) {
			MultiValueMap<String, Object> attributes = annotations.stream(annotationType)
					.filter(MergedAnnotationPredicates.unique(MergedAnnotation::getMetaTypes))
					.collect(MergedAnnotationCollectors.toMultiValueMap(Adapt.CLASS_TO_STRING));
			MergedAnnotation<A> annotation = annotations.get(annotationType);
			this.classLoader = context.getClassLoader();
			this.annotationType = annotationType;
			// Bean的名称
			this.names = extract(attributes, "name");
			// Bean上的注解
			this.annotations = extract(attributes, "annotation");
			// 忽略的Bean类型
			this.ignoredTypes = extract(attributes, "ignored", "ignoredType");
			// Bean的范型参数需要包含的范型类型
			this.parameterizedContainers = resolveWhenPossible(extract(attributes, "parameterizedContainer"));
			// 搜索策略
			this.strategy = annotation.getValue("search", SearchStrategy.class).orElse(null);
			// 需要存在或者不存在的类型，根据注解来判别
			Set<String> types = extractTypes(attributes);
			BeanTypeDeductionException deductionException = null;
			if (types.isEmpty() && this.names.isEmpty()) {
				try {
					// 推断该注解需要处理的类型
					types = deducedBeanType(context, metadata);
				}
				catch (BeanTypeDeductionException ex) {
					deductionException = ex;
				}
			}
			// 需要存在或者不存在的类型
			this.types = types;
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

		protected void validate(BeanTypeDeductionException ex) {
			if (!hasAtLeastOneElement(this.types, this.names, this.annotations)) {
				String message = getAnnotationName() + " did not specify a bean using type, name or annotation";
				if (ex == null) {
					throw new IllegalStateException(message);
				}
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

		private Set<String> deducedBeanType(ConditionContext context, AnnotatedTypeMetadata metadata) {
			if (metadata instanceof MethodMetadata && metadata.isAnnotated(Bean.class.getName())) {
				// @Bean的类型推断
				return deducedBeanTypeForBeanMethod(context, (MethodMetadata) metadata);
			}
			return Collections.emptySet();
		}

		private Set<String> deducedBeanTypeForBeanMethod(ConditionContext context, MethodMetadata metadata) {
			try {
				// 将@Bean的返回值类型作为结果
				Class<?> returnType = getReturnType(context, metadata);
				return Collections.singleton(returnType.getName());
			}
			catch (Throwable ex) {
				throw new BeanTypeDeductionException(metadata.getDeclaringClassName(), metadata.getMethodName(), ex);
			}
		}

		private Class<?> getReturnType(ConditionContext context, MethodMetadata metadata)
				throws ClassNotFoundException, LinkageError {
			// Safe to load at this point since we are in the REGISTER_BEAN phase
			ClassLoader classLoader = context.getClassLoader();
			// 直接加载
			Class<?> returnType = resolve(metadata.getReturnTypeName(), classLoader);
			// 范型的处理
			if (isParameterizedContainer(returnType)) {
				returnType = getReturnTypeGeneric(metadata, classLoader);
			}
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
			// 去除空的，跟基类
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
	 * 条件评估期间收集的结果。
	 */
	private static final class MatchResult {

		/**
		 * Bean 匹配的注解
		 */
		private final Map<String, Collection<String>> matchedAnnotations = new HashMap<>();

		/**
		 * 匹配上的名字
		 */
		private final List<String> matchedNames = new ArrayList<>();

		/**
		 * Bean 匹配的类型
		 */
		private final Map<String, Collection<String>> matchedTypes = new HashMap<>();

		/**
		 * 未匹配上的Bean注解
		 */
		private final List<String> unmatchedAnnotations = new ArrayList<>();

		/**
		 * 未匹配上的Bean名字
		 */
		private final List<String> unmatchedNames = new ArrayList<>();

		/**
		 * 未匹配上的Bean类型
		 */
		private final List<String> unmatchedTypes = new ArrayList<>();

		/**
		 * 所有匹配的名字
		 */
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

		boolean isAllMatched() {
			return this.unmatchedAnnotations.isEmpty()
					&& this.unmatchedNames.isEmpty()
					&& this.unmatchedTypes.isEmpty();
		}

		boolean isAnyMatched() {
			return (!this.matchedAnnotations.isEmpty())
					|| (!this.matchedNames.isEmpty())
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
	 * Exteption thrown when the bean type cannot be deduced.
	 */
	static final class BeanTypeDeductionException extends RuntimeException {

		private BeanTypeDeductionException(String className, String beanMethodName, Throwable cause) {
			super("Failed to deduce bean type for " + className + "." + beanMethodName, cause);
		}

	}

}
