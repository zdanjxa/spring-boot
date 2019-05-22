/*
 * Copyright 2012-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.context.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.RandomValuePropertySource;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

/**
 * {@link EnvironmentPostProcessor} that configures the context environment by loading
 * properties from well known file locations. By default properties will be loaded from
 * 'application.properties' and/or 'application.yml' files in the following locations:
 * <ul>
 * <li>classpath:</li>
 * <li>file:./</li>
 * <li>classpath:config/</li>
 * <li>file:./config/:</li>
 * </ul>
 * <p>
 * Alternative search locations and names can be specified using
 * {@link #setSearchLocations(String)} and {@link #setSearchNames(String)}.
 * <p>
 * Additional files will also be loaded based on active profiles. For example if a 'web'
 * profile is active 'application-web.properties' and 'application-web.yml' will be
 * considered.
 * <p>
 * The 'spring.config.name' property can be used to specify an alternative name to load
 * and the 'spring.config.location' property can be used to specify alternative search
 * locations or specific files.
 * <p>
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @author Andy Wilkinson
 * @author Eddú Meléndez
 * @author Madhura Bhave
 */
public class ConfigFileApplicationListener
		implements EnvironmentPostProcessor, SmartApplicationListener, Ordered {

	private static final String DEFAULT_PROPERTIES = "defaultProperties";

	// Note the order is from least to most specific (last one wins)
	private static final String DEFAULT_SEARCH_LOCATIONS = "classpath:/,classpath:/config/,file:./,file:./config/";

	private static final String DEFAULT_NAMES = "application";

	private static final Set<String> NO_SEARCH_NAMES = Collections.singleton(null);

	private static final Bindable<String[]> STRING_ARRAY = Bindable.of(String[].class);

	/**
	 * The "active profiles" property name.
	 */
	public static final String ACTIVE_PROFILES_PROPERTY = "spring.profiles.active";

	/**
	 * The "includes profiles" property name.
	 */
	public static final String INCLUDE_PROFILES_PROPERTY = "spring.profiles.include";

	/**
	 * The "config name" property name.
	 */
	public static final String CONFIG_NAME_PROPERTY = "spring.config.name";

	/**
	 * The "config location" property name.
	 */
	public static final String CONFIG_LOCATION_PROPERTY = "spring.config.location";

	/**
	 * The "config additional location" property name.
	 */
	public static final String CONFIG_ADDITIONAL_LOCATION_PROPERTY = "spring.config.additional-location";

	/**
	 * The default order for the processor.
	 */
	public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

	private final DeferredLog logger = new DeferredLog();

	private String searchLocations;

	private String names;

	private int order = DEFAULT_ORDER;

	@Override
	public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {//只支持对ApplicationEnvironmentPreparedEvent ApplicationPreparedEvent进行处理
		return ApplicationEnvironmentPreparedEvent.class.isAssignableFrom(eventType)
				|| ApplicationPreparedEvent.class.isAssignableFrom(eventType);
	}

	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		if (event instanceof ApplicationEnvironmentPreparedEvent) {//说明Spring环境准备好了
			onApplicationEnvironmentPreparedEvent(
					(ApplicationEnvironmentPreparedEvent) event);
		}
		if (event instanceof ApplicationPreparedEvent) {//说明Spring容器初始化好了
			onApplicationPreparedEvent(event);
		}
	}

	private void onApplicationEnvironmentPreparedEvent(
			ApplicationEnvironmentPreparedEvent event) {
		//加载在META-INF/spring.factories里指定的EnvironmentPostProcessor 类型
		List<EnvironmentPostProcessor> postProcessors = loadPostProcessors();
		postProcessors.add(this);//包含当前类,也是EnvironmentPostProcessor的实现
		AnnotationAwareOrderComparator.sort(postProcessors);//排序
		for (EnvironmentPostProcessor postProcessor : postProcessors) {//遍历执行
			postProcessor.postProcessEnvironment(event.getEnvironment(),
					event.getSpringApplication());
		}
	}

	List<EnvironmentPostProcessor> loadPostProcessors() {
		return SpringFactoriesLoader.loadFactories(EnvironmentPostProcessor.class,
				getClass().getClassLoader());
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment,
			SpringApplication application) {
		addPropertySources(environment, application.getResourceLoader());
	}

	private void onApplicationPreparedEvent(ApplicationEvent event) {
		this.logger.switchTo(ConfigFileApplicationListener.class);
		// 添加 PropertySourceOrderingPostProcessor 处理器
		addPostProcessors(((ApplicationPreparedEvent) event).getApplicationContext());
	}

	/**
	 * Add config file property sources to the specified environment.
	 * @param environment the environment to add source to
	 * @param resourceLoader the resource loader
	 * @see #addPostProcessors(ConfigurableApplicationContext)
	 */
	protected void addPropertySources(ConfigurableEnvironment environment,
			ResourceLoader resourceLoader) {
		//添加 RandomValuePropertySource 到 environment 中
		RandomValuePropertySource.addToEnvironment(environment);
		//加载
		new Loader(environment, resourceLoader).load();
	}

	/**
	 * Add appropriate post-processors to post-configure the property-sources.
	 * @param context the context to configure
	 */
	protected void addPostProcessors(ConfigurableApplicationContext context) {
		context.addBeanFactoryPostProcessor(
				new PropertySourceOrderingPostProcessor(context));
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	/**
	 * Set the search locations that will be considered as a comma-separated list. Each
	 * search location should be a directory path (ending in "/") and it will be prefixed
	 * by the file names constructed from {@link #setSearchNames(String) search names} and
	 * profiles (if any) plus file extensions supported by the properties loaders.
	 * Locations are considered in the order specified, with later items taking precedence
	 * (like a map merge).
	 * @param locations the search locations
	 */
	public void setSearchLocations(String locations) {
		Assert.hasLength(locations, "Locations must not be empty");
		this.searchLocations = locations;
	}

	/**
	 * Sets the names of the files that should be loaded (excluding file extension) as a
	 * comma-separated list.
	 * @param names the names to load
	 */
	public void setSearchNames(String names) {
		Assert.hasLength(names, "Names must not be empty");
		this.names = names;
	}

	/**
	 * {@link BeanFactoryPostProcessor} to re-order our property sources below any
	 * {@code @PropertySource} items added by the {@link ConfigurationClassPostProcessor}.
	 */
	private class PropertySourceOrderingPostProcessor
			implements BeanFactoryPostProcessor, Ordered {

		private ConfigurableApplicationContext context;

		PropertySourceOrderingPostProcessor(ConfigurableApplicationContext context) {
			this.context = context;
		}

		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE;
		}

		@Override
		public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
				throws BeansException {
			reorderSources(this.context.getEnvironment());
		}

		private void reorderSources(ConfigurableEnvironment environment) {
			//移除 DEFAULT_PROPERTIES 属性
			PropertySource<?> defaultProperties = environment.getPropertySources()
					.remove(DEFAULT_PROPERTIES);
			if (defaultProperties != null) {
				//添加属性到environment 尾部
				environment.getPropertySources().addLast(defaultProperties);
			}
		}

	}

	/**
	 * Loads candidate property sources and configures the active profiles.
	 * 加载指定的配置文件
	 */
	private class Loader {

		private final Log logger = ConfigFileApplicationListener.this.logger;

		private final ConfigurableEnvironment environment;

		private final PropertySourcesPlaceholdersResolver placeholdersResolver;

		private final ResourceLoader resourceLoader;

		private final List<PropertySourceLoader> propertySourceLoaders;

		/** 未处理的Profile集合 */
		private Deque<Profile> profiles;

		/** 已处理的Profile集合 */
		private List<Profile> processedProfiles;

		private boolean activatedProfiles;

		private Map<Profile, MutablePropertySources> loaded;

		private Map<DocumentsCacheKey, List<Document>> loadDocumentsCache = new HashMap<>();

		Loader(ConfigurableEnvironment environment, ResourceLoader resourceLoader) {
			this.environment = environment;
			this.placeholdersResolver = new PropertySourcesPlaceholdersResolver(
					this.environment);
			this.resourceLoader = (resourceLoader != null) ? resourceLoader
					: new DefaultResourceLoader();
			//加载`META-INF/spring.factories` 里的类名的数组,默认情况下返回PropertiesPropertySourceLoader、YamlPropertySourceLoader
			this.propertySourceLoaders = SpringFactoriesLoader.loadFactories(
					PropertySourceLoader.class, getClass().getClassLoader());
		}

		/**
		 * 加载配置
		 */
		public void load() {
			this.profiles = new LinkedList<>();
			this.processedProfiles = new LinkedList<>();
			this.activatedProfiles = false;
			this.loaded = new LinkedHashMap<>();
			//初始化Profile
			initializeProfiles();
			while (!this.profiles.isEmpty()) {
				Profile profile = this.profiles.poll();
				if (profile != null && !profile.isDefaultProfile()) {//添加非默认Profile到environment中
					addProfileToEnvironment(profile.getName());
				}
				//加载配置
				load(profile, this::getPositiveProfileFilter,
						addToLoaded(MutablePropertySources::addLast, false));//将Document的PropertySource添加到尾部
				this.processedProfiles.add(profile);
			}
			//加载Profile到environment中
			resetEnvironmentProfiles(this.processedProfiles);
			load(null, this::getNegativeProfileFilter,
					addToLoaded(MutablePropertySources::addFirst, true));//将Document的PropertySource添加到头部
			//将加载的配置对应的 MutablePropertySources 添加到environment中
			addLoadedPropertySources();
		}

		/**
		 * Initialize profile information from both the {@link Environment} active
		 * profiles and any {@code spring.profiles.active}/{@code spring.profiles.include}
		 * properties that are already set.
		 * 初始化SPring Profiles相关
		 */
		private void initializeProfiles() {
			// The default profile for these purposes is represented as null. We add it
			// first so that it is processed first and has lowest priority.
			//首先添加null是希望默认的配置文件优先被处理
			this.profiles.add(null);
			//从配置中加载激活的Profile (spring.profiles.active  spring.profiles.include)
			Set<Profile> activatedViaProperty = getProfilesActivatedViaProperty();
			//添加激活的Profile(eg:SpringApplication.additionalProfiles)
			this.profiles.addAll(getOtherActiveProfiles(activatedViaProperty));
			// Any pre-existing active profiles set via property sources (e.g.
			// System properties) take precedence over those added in config files.
			addActiveProfiles(activatedViaProperty);
			if (this.profiles.size() == 1) { // only has null profile
				//如果没有激活的profile则使用默认 spring.profiles.default
				for (String defaultProfileName : this.environment.getDefaultProfiles()) {
					Profile defaultProfile = new Profile(defaultProfileName, true);
					this.profiles.add(defaultProfile);
				}
			}
		}

		private Set<Profile> getProfilesActivatedViaProperty() {
			if (!this.environment.containsProperty(ACTIVE_PROFILES_PROPERTY)
					&& !this.environment.containsProperty(INCLUDE_PROFILES_PROPERTY)) {
				return Collections.emptySet();
			}
			Binder binder = Binder.get(this.environment);
			Set<Profile> activeProfiles = new LinkedHashSet<>();
			activeProfiles.addAll(getProfiles(binder, INCLUDE_PROFILES_PROPERTY));
			activeProfiles.addAll(getProfiles(binder, ACTIVE_PROFILES_PROPERTY));
			return activeProfiles;
		}

		private List<Profile> getOtherActiveProfiles(Set<Profile> activatedViaProperty) {
			return Arrays.stream(this.environment.getActiveProfiles()).map(Profile::new)
					.filter((profile) -> !activatedViaProperty.contains(profile))
					.collect(Collectors.toList());
		}

		void addActiveProfiles(Set<Profile> profiles) {
			if (profiles.isEmpty()) {
				return;
			}
			if (this.activatedProfiles) {//如果已经标记则返回
				if (this.logger.isDebugEnabled()) {
					this.logger.debug("Profiles already activated, '" + profiles
							+ "' will not be applied");
				}
				return;
			}
			this.profiles.addAll(profiles);
			if (this.logger.isDebugEnabled()) {
				this.logger.debug("Activated activeProfiles "
						+ StringUtils.collectionToCommaDelimitedString(profiles));
			}
			this.activatedProfiles = true;
			//移除Profiles中默认的配置
			removeUnprocessedDefaultProfiles();
		}

		private void removeUnprocessedDefaultProfiles() {
			this.profiles.removeIf(
					(profile) -> (profile != null && profile.isDefaultProfile()));
		}

		private DocumentFilter getPositiveProfileFilter(Profile profile) {
			return (Document document) -> {
				if (profile == null) {//表示profile 为空时,document.profile也为空
					return ObjectUtils.isEmpty(document.getProfiles());
				}
				//document必须包含profile 并且  environment.activeProfiles 包含 document.profiles
				return ObjectUtils.containsElement(document.getProfiles(),
						profile.getName())
						&& this.environment
								.acceptsProfiles(Profiles.of(document.getProfiles()));
			};
		}

		private DocumentFilter getNegativeProfileFilter(Profile profile) {
			return (Document document) -> (profile == null  //为空
					&& !ObjectUtils.isEmpty(document.getProfiles()) && this.environment  //document.profile非空
							.acceptsProfiles(Profiles.of(document.getProfiles())));//environment.activeProfiles 包含 document.profiles
		}

		/**
		 * 将 Document 的 PropertySource ，添加到 Loader.loaded 中
		 * @param addMethod
		 * @param checkForExisting 是否校验已存在的
		 * @return
		 */
		private DocumentConsumer addToLoaded(
				BiConsumer<MutablePropertySources, PropertySource<?>> addMethod,
				boolean checkForExisting) {
			return (profile, document) -> {
				if (checkForExisting) {
					for (MutablePropertySources merged : this.loaded.values()) {
						if (merged.contains(document.getPropertySource().getName())) {
							return;
						}
					}
				}
				//获得profile对应的MutablePropertySources
				MutablePropertySources merged = this.loaded.computeIfAbsent(profile,
						(k) -> new MutablePropertySources());
				//将加载的documenet合并到merged中
				addMethod.accept(merged, document.getPropertySource());
			};
		}

		private void load(Profile profile, DocumentFilterFactory filterFactory,
				DocumentConsumer consumer) {
			//获得检索配置的路径
			getSearchLocations().forEach((location) -> {
				boolean isFolder = location.endsWith("/");//是否文件夹
				Set<String> names = isFolder ? getSearchNames() : NO_SEARCH_NAMES;//获得检索配置的文件名集合
				//遍历并加载配置文件
				names.forEach(
						(name) -> load(location, name, profile, filterFactory, consumer));
			});
		}

		private void load(String location, String name, Profile profile,
				DocumentFilterFactory filterFactory, DocumentConsumer consumer) {
			if (!StringUtils.hasText(name)) {
				for (PropertySourceLoader loader : this.propertySourceLoaders) {
					if (canLoadFileExtension(loader, location)) {
						load(loader, location, profile,
								filterFactory.getDocumentFilter(profile), consumer);
						return;
					}
				}
			}
			Set<String> processed = new HashSet<>();
			//一个PropertySourceLoader加载一种后缀的文件,此处一般为两个PropertySourceLoader ;PropertiesPropertySourceLoader 处理xml跟properties YamlPropertySourceLoader 处理 yml yaml
			for (PropertySourceLoader loader : this.propertySourceLoaders) {
				for (String fileExtension : loader.getFileExtensions()) {
					if (processed.add(fileExtension)) {
						loadForFileExtension(loader, location + name, "." + fileExtension,
								profile, filterFactory, consumer);
					}
				}
			}
		}

		private boolean canLoadFileExtension(PropertySourceLoader loader, String name) {
			return Arrays.stream(loader.getFileExtensions())
					.anyMatch((fileExtension) -> StringUtils.endsWithIgnoreCase(name,
							fileExtension));
		}

		/**
		 * 加载 Profile 指定的配置文件（带后缀）
		 * @param loader 加载器
		 * @param prefix 文件名
		 * @param fileExtension 文件后缀
		 * @param profile 激活文件
		 * @param filterFactory
		 * @param consumer
		 */
		private void loadForFileExtension(PropertySourceLoader loader, String prefix,
				String fileExtension, Profile profile,
				DocumentFilterFactory filterFactory, DocumentConsumer consumer) {
			DocumentFilter defaultFilter = filterFactory.getDocumentFilter(null);
			DocumentFilter profileFilter = filterFactory.getDocumentFilter(profile);
			if (profile != null) {
				// Try profile-specific file & profile section in profile file (gh-340)
				String profileSpecificFile = prefix + "-" + profile + fileExtension;
				load(loader, profileSpecificFile, profile, defaultFilter, consumer);//情况1:未配置spring.profile
				load(loader, profileSpecificFile, profile, profileFilter, consumer);//情况2:已配置spring.profile
				// Try profile specific sections in files we've already processed
				//情况3: 之前读取 Profile 对应的配置文件，也可被当前 Profile 所读取。(eg:假设之前读取了profile为test的配置文件(application-test.yml),里面配置了spring.profile=dev,prod,
				// 那么如果当前读取的profile为dev,则也能读取application-test.yml这个配置文件)
				for (Profile processedProfile : this.processedProfiles) {
					if (processedProfile != null) {
						String previouslyLoaded = prefix + "-" + processedProfile
								+ fileExtension;
						load(loader, previouslyLoaded, profile, profileFilter, consumer);//注意:此处传入的profile为当前profile
					}
				}
			}
			// Also try the profile-specific section (if any) of the normal file
			//加载（无需带 Profile）指定的配置文件
			load(loader, prefix + fileExtension, profile, profileFilter, consumer);
		}

		private void load(PropertySourceLoader loader, String location, Profile profile,
				DocumentFilter filter, DocumentConsumer consumer) {
			try {
				Resource resource = this.resourceLoader.getResource(location);
				if (resource == null || !resource.exists()) {//资源文件不存在
					if (this.logger.isTraceEnabled()) {
						StringBuilder description = getDescription(
								"Skipped missing config ", location, resource, profile);
						this.logger.trace(description);
					}
					return;
				}
				if (!StringUtils.hasText(
						StringUtils.getFilenameExtension(resource.getFilename()))) {//没有后缀的文件不读取
					if (this.logger.isTraceEnabled()) {
						StringBuilder description = getDescription(
								"Skipped empty config extension ", location, resource,
								profile);
						this.logger.trace(description);
					}
					return;
				}
				String name = "applicationConfig: [" + location + "]";
				List<Document> documents = loadDocuments(loader, name, resource);
				if (CollectionUtils.isEmpty(documents)) {//没有加载到文档
					if (this.logger.isTraceEnabled()) {
						StringBuilder description = getDescription(
								"Skipped unloaded config ", location, resource, profile);
						this.logger.trace(description);
					}
					return;
				}
				//使用 DocumentFilter 过滤匹配的 Document ，添加到 loaded 数组中
				List<Document> loaded = new ArrayList<>();
				for (Document document : documents) {
					if (filter.match(document)) {
						addActiveProfiles(document.getActiveProfiles());
						addIncludedProfiles(document.getIncludeProfiles());
						loaded.add(document);
					}
				}
				Collections.reverse(loaded);
				//使用 DocumentConsumer 进行消费 Document ，添加到本地的 loaded 中。
				if (!loaded.isEmpty()) {
					loaded.forEach((document) -> consumer.accept(profile, document));
					if (this.logger.isDebugEnabled()) {
						StringBuilder description = getDescription("Loaded config file ",
								location, resource, profile);
						this.logger.debug(description);
					}
				}
			}
			catch (Exception ex) {
				throw new IllegalStateException("Failed to load property "
						+ "source from location '" + location + "'", ex);
			}
		}

		private void addIncludedProfiles(Set<Profile> includeProfiles) {
			// 整体逻辑是 includeProfiles - processedProfiles + profiles
			LinkedList<Profile> existingProfiles = new LinkedList<>(this.profiles);
			this.profiles.clear();
			this.profiles.addAll(includeProfiles);
			this.profiles.removeAll(this.processedProfiles);
			this.profiles.addAll(existingProfiles);
		}

		/**
		 * 加载配置文件，并返回 Document 数组
		 * @param loader
		 * @param name
		 * @param resource
		 * @return
		 * @throws IOException
		 */
		private List<Document> loadDocuments(PropertySourceLoader loader, String name,
				Resource resource) throws IOException {
			DocumentsCacheKey cacheKey = new DocumentsCacheKey(loader, resource);
			List<Document> documents = this.loadDocumentsCache.get(cacheKey);
			if (documents == null) {//如果缓存中不存在则使用PropertySourceLoader加载指定配置文件
				List<PropertySource<?>> loaded = loader.load(name, resource);
				documents = asDocuments(loaded);//将加载的PropertySource转换成Document
				this.loadDocumentsCache.put(cacheKey, documents);
			}
			return documents;
		}

		private List<Document> asDocuments(List<PropertySource<?>> loaded) {
			if (loaded == null) {
				return Collections.emptyList();
			}
			return loaded.stream().map((propertySource) -> {
				Binder binder = new Binder(
						ConfigurationPropertySources.from(propertySource),
						this.placeholdersResolver);
				return new Document(propertySource,
						binder.bind("spring.profiles", STRING_ARRAY).orElse(null),//读取 spring.profiles 配置
						getProfiles(binder, ACTIVE_PROFILES_PROPERTY),//读取 spring.profiles.active 配置
						getProfiles(binder, INCLUDE_PROFILES_PROPERTY));//读取 spring.profiles.include 配置
			}).collect(Collectors.toList());
		}

		private StringBuilder getDescription(String prefix, String location,
				Resource resource, Profile profile) {
			StringBuilder result = new StringBuilder(prefix);
			try {
				if (resource != null) {
					String uri = resource.getURI().toASCIIString();
					result.append("'");
					result.append(uri);
					result.append("' (");
					result.append(location);
					result.append(")");
				}
			}
			catch (IOException ex) {
				result.append(location);
			}
			if (profile != null) {
				result.append(" for profile ");
				result.append(profile);
			}
			return result;
		}

		private Set<Profile> getProfiles(Binder binder, String name) {
			return binder.bind(name, STRING_ARRAY).map(this::asProfileSet)
					.orElse(Collections.emptySet());
		}

		private Set<Profile> asProfileSet(String[] profileNames) {
			List<Profile> profiles = new ArrayList<>();
			for (String profileName : profileNames) {
				profiles.add(new Profile(profileName));
			}
			return new LinkedHashSet<>(profiles);
		}

		private void addProfileToEnvironment(String profile) {
			for (String activeProfile : this.environment.getActiveProfiles()) {
				if (activeProfile.equals(profile)) {
					return;
				}
			}
			this.environment.addActiveProfile(profile);
		}

		/**
		 * 获得要检索配置的路径<br/>
		 * 1.如果配置了 spring.config.location 则作为检索配置路径<br/>
		 * 2.如果配置了 soring.config.additional-location 则作为附件检索配置路径(跟 DEFAULT_SEARCH_LOCATIONS 取并集)<br/>
		 * 3.默认返回 DEFAULT_SEARCH_LOCATIONS
		 * @return
		 */
		private Set<String> getSearchLocations() {
			//获得 spring.config.location 的值
			if (this.environment.containsProperty(CONFIG_LOCATION_PROPERTY)) {
				return getSearchLocations(CONFIG_LOCATION_PROPERTY);
			}
			// 获得 `"spring.config.additional-location"` 对应的配置的值
			Set<String> locations = getSearchLocations(
					CONFIG_ADDITIONAL_LOCATION_PROPERTY);
			// 添加 searchLocations 到 locations 中
			locations.addAll(
					asResolvedSet(ConfigFileApplicationListener.this.searchLocations,
							DEFAULT_SEARCH_LOCATIONS));
			return locations;
		}

		private Set<String> getSearchLocations(String propertyName) {
			Set<String> locations = new LinkedHashSet<>();
			if (this.environment.containsProperty(propertyName)) {
				for (String path : asResolvedSet(
						this.environment.getProperty(propertyName), null)) {
					if (!path.contains("$")) {
						path = StringUtils.cleanPath(path);
						if (!ResourceUtils.isUrl(path)) {
							path = ResourceUtils.FILE_URL_PREFIX + path;
						}
					}
					locations.add(path);
				}
			}
			return locations;
		}

		private Set<String> getSearchNames() {
			//获得 spring.config.name 的值
			if (this.environment.containsProperty(CONFIG_NAME_PROPERTY)) {
				String property = this.environment.getProperty(CONFIG_NAME_PROPERTY);
				return asResolvedSet(property, null);
			}
			//添加 names 或 DEFAULT_NAMES 到locations中
			return asResolvedSet(ConfigFileApplicationListener.this.names, DEFAULT_NAMES);
		}

		private Set<String> asResolvedSet(String value, String fallback) {
			List<String> list = Arrays.asList(StringUtils.trimArrayElements(
					StringUtils.commaDelimitedListToStringArray((value != null)
							? this.environment.resolvePlaceholders(value) : fallback)));
			Collections.reverse(list);
			return new LinkedHashSet<>(list);
		}

		/**
		 * This ensures that the order of active profiles in the {@link Environment}
		 * matches the order in which the profiles were processed.
		 * 将需要加载的profile添加到environment
		 * @param processedProfiles the processed profiles
		 */
		private void resetEnvironmentProfiles(List<Profile> processedProfiles) {
			String[] names = processedProfiles.stream()
					.filter((profile) -> profile != null && !profile.isDefaultProfile())
					.map(Profile::getName).toArray(String[]::new);
			this.environment.setActiveProfiles(names);
		}

		private void addLoadedPropertySources() {
			MutablePropertySources destination = this.environment.getPropertySources();
			List<MutablePropertySources> loaded = new ArrayList<>(this.loaded.values());
			Collections.reverse(loaded);//反转是因为后面的profile优先级更高 eg:spring.profiles.active=prod,dev 则优先级是dev>prod
			String lastAdded = null;
			Set<String> added = new HashSet<>();
			for (MutablePropertySources sources : loaded) {//遍历loader
				for (PropertySource<?> source : sources) {//再次遍历sources是因为一个Profile可能对应多个配置文件 eg: Profile为dev,对应application-dev.yml application-dev.properties
					if (added.add(source.getName())) {
						addLoadedPropertySource(destination, lastAdded, source);
						lastAdded = source.getName();
					}
				}
			}
		}

		private void addLoadedPropertySource(MutablePropertySources destination,
				String lastAdded, PropertySource<?> source) {
			if (lastAdded == null) {
				if (destination.contains(DEFAULT_PROPERTIES)) {
					destination.addBefore(DEFAULT_PROPERTIES, source);
				}
				else {
					destination.addLast(source);
				}
			}
			else {
				destination.addAfter(lastAdded, source);
			}
		}

	}

	/**
	 * A Spring Profile that can be loaded.
	 */
	private static class Profile {

		private final String name;

		private final boolean defaultProfile;

		Profile(String name) {
			this(name, false);
		}

		Profile(String name, boolean defaultProfile) {
			Assert.notNull(name, "Name must not be null");
			this.name = name;
			this.defaultProfile = defaultProfile;
		}

		public String getName() {
			return this.name;
		}

		public boolean isDefaultProfile() {
			return this.defaultProfile;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this) {
				return true;
			}
			if (obj == null || obj.getClass() != getClass()) {
				return false;
			}
			return ((Profile) obj).name.equals(this.name);
		}

		@Override
		public int hashCode() {
			return this.name.hashCode();
		}

		@Override
		public String toString() {
			return this.name;
		}

	}

	/**
	 * Cache key used to save loading the same document multiple times.
	 * 表示加载 Documents 的缓存 KEY (因为可能重复加载配置)
	 */
	private static class DocumentsCacheKey {

		private final PropertySourceLoader loader;

		private final Resource resource;

		DocumentsCacheKey(PropertySourceLoader loader, Resource resource) {
			this.loader = loader;
			this.resource = resource;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			DocumentsCacheKey other = (DocumentsCacheKey) obj;
			return this.loader.equals(other.loader)
					&& this.resource.equals(other.resource);
		}

		@Override
		public int hashCode() {
			return this.loader.hashCode() * 31 + this.resource.hashCode();
		}

	}

	/**
	 * A single document loaded by a {@link PropertySourceLoader}.
	 * 用于封装 PropertySourceLoader 加载配置文件后
	 */
	private static class Document {

		private final PropertySource<?> propertySource;

		/** 对应 spring.profiles 属性值 */
		private String[] profiles;

		/** 对应 spring.profiles.active 属性值 */
		private final Set<Profile> activeProfiles;

		/** 对应 spring.profiles.include 属性值 */
		private final Set<Profile> includeProfiles;

		Document(PropertySource<?> propertySource, String[] profiles,
				Set<Profile> activeProfiles, Set<Profile> includeProfiles) {
			this.propertySource = propertySource;
			this.profiles = profiles;
			this.activeProfiles = activeProfiles;
			this.includeProfiles = includeProfiles;
		}

		public PropertySource<?> getPropertySource() {
			return this.propertySource;
		}

		public String[] getProfiles() {
			return this.profiles;
		}

		public Set<Profile> getActiveProfiles() {
			return this.activeProfiles;
		}

		public Set<Profile> getIncludeProfiles() {
			return this.includeProfiles;
		}

		@Override
		public String toString() {
			return this.propertySource.toString();
		}

	}

	/**
	 * Factory used to create a {@link DocumentFilter}.
	 */
	@FunctionalInterface
	private interface DocumentFilterFactory {

		/**
		 * Create a filter for the given profile.
		 * @param profile the profile or {@code null}
		 * @return the filter
		 */
		DocumentFilter getDocumentFilter(Profile profile);

	}

	/**
	 * Filter used to restrict when a {@link Document} is loaded.
	 * 匹配配置加载后的 Document 对象
	 */
	@FunctionalInterface
	private interface DocumentFilter {

		boolean match(Document document);

	}

	/**
	 * Consumer used to handle a loaded {@link Document}.
	 * 处理传入的DOcument
	 */
	@FunctionalInterface
	private interface DocumentConsumer {

		void accept(Profile profile, Document document);

	}

}
