/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.registry.integration;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.RegistryFactory;
import org.apache.dubbo.registry.RegistryService;
import org.apache.dubbo.registry.retry.ReExportTask;
import org.apache.dubbo.registry.support.SkipFailbackWrapperException;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.Configurator;
import org.apache.dubbo.rpc.cluster.governance.GovernanceRuleRepository;
import org.apache.dubbo.rpc.cluster.support.MergeableCluster;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ProviderModel;
import org.apache.dubbo.rpc.protocol.InvokerWrapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.CLUSTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_VERSION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.EXTRA_KEYS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.HIDE_KEY_PREFIX;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LOADBALANCE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.RELEASE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMESTAMP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.FilterConstants.VALIDATION_KEY;
import static org.apache.dubbo.common.constants.QosConstants.ACCEPT_FOREIGN_IP;
import static org.apache.dubbo.common.constants.QosConstants.QOS_ENABLE;
import static org.apache.dubbo.common.constants.QosConstants.QOS_HOST;
import static org.apache.dubbo.common.constants.QosConstants.QOS_PORT;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.OVERRIDE_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTERS_CATEGORY;
import static org.apache.dubbo.common.utils.UrlUtils.classifyUrls;
import static org.apache.dubbo.registry.Constants.CONFIGURATORS_SUFFIX;
import static org.apache.dubbo.registry.Constants.CONSUMER_PROTOCOL;
import static org.apache.dubbo.registry.Constants.DEFAULT_REGISTRY;
import static org.apache.dubbo.registry.Constants.DEFAULT_REGISTRY_RETRY_PERIOD;
import static org.apache.dubbo.registry.Constants.PROVIDER_PROTOCOL;
import static org.apache.dubbo.registry.Constants.REGISTER_IP_KEY;
import static org.apache.dubbo.registry.Constants.REGISTER_KEY;
import static org.apache.dubbo.registry.Constants.REGISTRY_RETRY_PERIOD_KEY;
import static org.apache.dubbo.registry.Constants.SIMPLIFIED_KEY;
import static org.apache.dubbo.remoting.Constants.BIND_IP_KEY;
import static org.apache.dubbo.remoting.Constants.BIND_PORT_KEY;
import static org.apache.dubbo.remoting.Constants.CHECK_KEY;
import static org.apache.dubbo.remoting.Constants.CODEC_KEY;
import static org.apache.dubbo.remoting.Constants.CONNECTIONS_KEY;
import static org.apache.dubbo.remoting.Constants.EXCHANGER_KEY;
import static org.apache.dubbo.remoting.Constants.SERIALIZATION_KEY;
import static org.apache.dubbo.rpc.Constants.DEPRECATED_KEY;
import static org.apache.dubbo.rpc.Constants.INTERFACES;
import static org.apache.dubbo.rpc.Constants.MOCK_KEY;
import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.EXPORT_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.WARMUP_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.WEIGHT_KEY;

/**
 * RegistryProtocol
 */
public class RegistryProtocol implements Protocol {
    public static final String[] DEFAULT_REGISTER_PROVIDER_KEYS = {
            APPLICATION_KEY, CODEC_KEY, EXCHANGER_KEY, SERIALIZATION_KEY, CLUSTER_KEY, CONNECTIONS_KEY, DEPRECATED_KEY,
            GROUP_KEY, LOADBALANCE_KEY, MOCK_KEY, PATH_KEY, TIMEOUT_KEY, TOKEN_KEY, VERSION_KEY, WARMUP_KEY,
            WEIGHT_KEY, TIMESTAMP_KEY, DUBBO_VERSION_KEY, RELEASE_KEY
    };

    public static final String[] DEFAULT_REGISTER_CONSUMER_KEYS = {
            APPLICATION_KEY, VERSION_KEY, GROUP_KEY, DUBBO_VERSION_KEY, RELEASE_KEY
    };

    private final static Logger logger = LoggerFactory.getLogger(RegistryProtocol.class);
    private final Map<URL, NotifyListener> overrideListeners = new ConcurrentHashMap<>();
    private final Map<String, ServiceConfigurationListener> serviceConfigurationListeners = new ConcurrentHashMap<>();
    private final ProviderConfigurationListener providerConfigurationListener = new ProviderConfigurationListener();
    //To solve the problem of RMI repeated exposure port conflicts, the services that have been exposed are no longer exposed.
    //providerurl <--> exporter
    private final ConcurrentMap<String, ExporterChangeableWrapper<?>> bounds = new ConcurrentHashMap<>();
    private Protocol protocol;
    private RegistryFactory registryFactory;
    private ProxyFactory proxyFactory;

    private ConcurrentMap<URL, ReExportTask> reExportFailedTasks = new ConcurrentHashMap<>();
    private HashedWheelTimer retryTimer = new HashedWheelTimer(new NamedThreadFactory("DubboReexportTimer", true), DEFAULT_REGISTRY_RETRY_PERIOD, TimeUnit.MILLISECONDS, 128);

    //Filter the parameters that do not need to be output in url(Starting with .)
    private static String[] getFilteredKeys(URL url) {
        Map<String, String> params = url.getParameters();
        if (CollectionUtils.isNotEmptyMap(params)) {
            return params.keySet().stream()
                    .filter(k -> k.startsWith(HIDE_KEY_PREFIX))
                    .toArray(String[]::new);
        } else {
            return new String[0];
        }
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistryFactory(RegistryFactory registryFactory) {
        this.registryFactory = registryFactory;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    @Override
    public int getDefaultPort() {
        return 9090;
    }

    public Map<URL, NotifyListener> getOverrideListeners() {
        return overrideListeners;
    }

    private void register(URL registryUrl, URL registeredProviderUrl) {
        Registry registry = registryFactory.getRegistry(registryUrl);
        registry.register(registeredProviderUrl);
    }

    private void registerStatedUrl(URL registryUrl, URL registeredProviderUrl, boolean registered) {
        // 从ServiceRepository获取ProviderModel，ProviderModel已经在ServiceConfig的doExportUrls方法中创建了
        ProviderModel model = ApplicationModel.getProviderModel(registeredProviderUrl.getServiceKey());
        model.addStatedUrl(new ProviderModel.RegisterStatedURL(
                registeredProviderUrl,
                registryUrl,
                registered));
    }

    @Override
    public <T> Exporter<T> export(final Invoker<T> originInvoker) throws RpcException {
        // 获取url的registry部分，如原url为：
        // registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=first-dubbo-provider&dubbo=2.0.2&export=dubbo%3A%2F%2F192.168.89.101%3A20880%2Fcom.apache.dubbo.demo.api.GreetingService%3Fanyhost%3Dtrue%26application%3Dfirst-dubbo-provider%26bind.ip%3D192.168.89.101%26bind.port%3D20880%26default%3Dtrue%26deprecated%3Dfalse%26dubbo%3D2.0.2%26dynamic%3Dtrue%26generic%3Dfalse%26group%3Ddubbo%26interface%3Dcom.apache.dubbo.demo.api.GreetingService%26methods%3DsayHello%2CtestGeneric%26pid%3D58470%26release%3D%26revision%3D1.0.0%26side%3Dprovider%26timestamp%3D1611584616261%26version%3D1.0.0&pid=58470&registry=zookeeper&timestamp=1611584616250
        // registryUrl为：
        // zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=first-dubbo-provider&dubbo=2.0.2&export=dubbo%3A%2F%2F192.168.89.101%3A20880%2Fcom.apache.dubbo.demo.api.GreetingService%3Fanyhost%3Dtrue%26application%3Dfirst-dubbo-provider%26bind.ip%3D192.168.89.101%26bind.port%3D20880%26default%3Dtrue%26deprecated%3Dfalse%26dubbo%3D2.0.2%26dynamic%3Dtrue%26generic%3Dfalse%26group%3Ddubbo%26interface%3Dcom.apache.dubbo.demo.api.GreetingService%26methods%3DsayHello%2CtestGeneric%26pid%3D58470%26release%3D%26revision%3D1.0.0%26side%3Dprovider%26timestamp%3D1611584616261%26version%3D1.0.0&pid=58470&timestamp=1611584616250
        // 主要是从原url读取registry参数的值，保存到url最前头的protocol部分
        URL registryUrl = getRegistryUrl(originInvoker);
        // url to export locally
        // 原url的export参数部分
        // dubbo://192.168.89.101:20880/com.apache.dubbo.demo.api.GreetingService?anyhost=true&application=first-dubbo-provider&bind.ip=192.168.89.101&bind.port=20880&default=true&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=dubbo&interface=com.apache.dubbo.demo.api.GreetingService&methods=sayHello,testGeneric&pid=58470&release=&revision=1.0.0&side=provider&timestamp=1611584616261&version=1.0.0
        URL providerUrl = getProviderUrl(originInvoker);

        // Subscribe the override data
        // FIXME When the provider subscribes, it will affect the scene : a certain JVM exposes the service and call
        //  the same service. Because the subscribed is cached key with the name of the service, it causes the
        //  subscription information to cover.
        // 根据providerUrl创建subscribeUrl，主要是设置protocol为provider，添加category=configurators&check=false参数，如：
        // provider://192.168.89.101:20880/com.apache.dubbo.demo.api.GreetingService?anyhost=true&application=first-dubbo-provider&bind.ip=192.168.89.101&bind.port=20880&category=configurators&check=false&default=true&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&group=dubbo&interface=com.apache.dubbo.demo.api.GreetingService&methods=sayHello,testGeneric&pid=58803&release=&revision=1.0.0&side=provider&timestamp=1611585342556&version=1.0.0
        final URL overrideSubscribeUrl = getSubscribedOverrideUrl(providerUrl);
        // OverrideListener实现了NotifyListener接口，当overrideSubscribeUrl对应的服务在注册中心发生变化时会被调用，主要用于动态修改服务端配置
        final OverrideListener overrideSubscribeListener = new OverrideListener(overrideSubscribeUrl, originInvoker);
        overrideListeners.put(overrideSubscribeUrl, overrideSubscribeListener);

        // 遍历Configurator以支持定制化url
        providerUrl = overrideUrlWithConfig(providerUrl, overrideSubscribeListener);
        // export invoker
        // 调用protocol的export方法，这里执行的真正的export过程，默认实现在DubboProtocol
        final ExporterChangeableWrapper<T> exporter = doLocalExport(originInvoker, providerUrl);

        // url to registry
        // 获取url对应的Registry
        final Registry registry = getRegistry(originInvoker);
        // 创建将被保存在注册中心的url，默认情况下会参数providerUrl中一些参数后返回
        final URL registeredProviderUrl = getUrlToRegistry(providerUrl, registryUrl);

        // decide if we need to delay publish
        // 是否需要注册到注册中心
        boolean register = providerUrl.getParameter(REGISTER_KEY, true);
        if (register) {
            // 调用registry对象的registry方法，如果以zk作为注册中心，这一步会在zk创建一个节点，节点路径如：
            // /dubbo/com.apache.dubbo.demo.api.GreetingService/providers/dubbo%3A%2F%2F172.19.92.226%3A20880%2Fcom.apache.dubbo.demo.api.GreetingService%3Fanyhost%3Dtrue%26application%3Dfirst-dubbo-provider%26default%3Dtrue%26deprecated%3Dfalse%26dubbo%3D2.0.2%26dynamic%3Dtrue%26generic%3Dfalse%26group%3Ddubbo%26interface%3Dcom.apache.dubbo.demo.api.GreetingService%26methods%3DsayHello%2CtestGeneric%26pid%3D89484%26release%3D%26revision%3D1.0.0%26side%3Dprovider%26timestamp%3D1611730922954%26version%3D1.0.0
            register(registryUrl, registeredProviderUrl);
        }

        // register stated url on provider model
        // 保存url信息到ProviderModel对象
        registerStatedUrl(registryUrl, registeredProviderUrl, register);


        exporter.setRegisterUrl(registeredProviderUrl);
        exporter.setSubscribeUrl(overrideSubscribeUrl);

        // Deprecated! Subscribe to override rules in 2.6.x or before.
        // 订阅当前服务在注册中心的变化，发生变化时通知overrideSubscribeListener
        registry.subscribe(overrideSubscribeUrl, overrideSubscribeListener);

        // 获取RegistryProtocolListener实例并调用onExport方法
        notifyExport(exporter);
        //Ensure that a new exporter instance is returned every time export
        return new DestroyableExporter<>(exporter);
    }

    private <T> void notifyExport(ExporterChangeableWrapper<T> exporter) {
        List<RegistryProtocolListener> listeners = ExtensionLoader.getExtensionLoader(RegistryProtocolListener.class)
                .getActivateExtension(exporter.getOriginInvoker().getUrl(), "registry.protocol.listener");
        if (CollectionUtils.isNotEmpty(listeners)) {
            for (RegistryProtocolListener listener : listeners) {
                listener.onExport(this, exporter);
            }
        }
    }

    private URL overrideUrlWithConfig(URL providerUrl, OverrideListener listener) {
        providerUrl = providerConfigurationListener.overrideUrl(providerUrl);
        ServiceConfigurationListener serviceConfigurationListener = new ServiceConfigurationListener(providerUrl, listener);
        serviceConfigurationListeners.put(providerUrl.getServiceKey(), serviceConfigurationListener);
        return serviceConfigurationListener.overrideUrl(providerUrl);
    }

    @SuppressWarnings("unchecked")
    private <T> ExporterChangeableWrapper<T> doLocalExport(final Invoker<T> originInvoker, URL providerUrl) {
        String key = getCacheKey(originInvoker);

        return (ExporterChangeableWrapper<T>) bounds.computeIfAbsent(key, s -> {
            // InvokerDelegate持有invoker和url，通过originInvoker的getUrl方法可以直接获取url，但是这样获取到的url的前缀是registry的
            // InvokerDelegate的作用就是以providerUrl作为getUrl的结果，而不是直接返回originInvoker.getUrl
            Invoker<?> invokerDelegate = new InvokerDelegate<>(originInvoker, providerUrl);
            // protocol是Protocol接口的适配器，所以这里的protocol.export的调用链实际上是：
            // ProtocolFilterWrapper -> ProtocolListenerWrapper -> DubboProtocol，调用链没有RegistryProtocol是因为这里传入
            // protocol的invokerDelegate的getUrl返回providerUrl，是去掉registry前缀和org.apache.dubbo.registry.RegistryService
            // 的url，所以该url的protocol为dubbo而不是registry，而protocol是个适配器，实际执行protocol.export时会获取DubboProtocol，
            // 同时ProtocolFilterWrapper和ProtocolListenerWrapper作为wrapper封装了DubboProtocol
            // ExporterChangeableWrapper持有由protocol.export返回的invoker和原始的invoker，能够在必要的时候有选择的返回两个invoker
            // 中的一个，同时实现了unexport逻辑
            return new ExporterChangeableWrapper<>((Exporter<T>) protocol.export(invokerDelegate), originInvoker);
        });
    }

    public <T> void reExport(Exporter<T> exporter, URL newInvokerUrl) {
        if (exporter instanceof ExporterChangeableWrapper) {
            ExporterChangeableWrapper<T> exporterWrapper = (ExporterChangeableWrapper<T>) exporter;
            Invoker<T> originInvoker = exporterWrapper.getOriginInvoker();
            reExport(originInvoker, newInvokerUrl);
        }
    }

    /**
     * Reexport the invoker of the modified url
     *
     * @param originInvoker
     * @param newInvokerUrl
     * @param <T>
     */
    @SuppressWarnings("unchecked")
    public <T> void reExport(final Invoker<T> originInvoker, URL newInvokerUrl) {
        String key = getCacheKey(originInvoker);
        ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
        URL registeredUrl = exporter.getRegisterUrl();

        URL registryUrl = getRegistryUrl(originInvoker);
        URL newProviderUrl = getUrlToRegistry(newInvokerUrl, registryUrl);

        // update local exporter
        Invoker<T> invokerDelegate = new InvokerDelegate<T>(originInvoker, newInvokerUrl);
        exporter.setExporter(protocol.export(invokerDelegate));

        // update registry
        if (!newProviderUrl.equals(registeredUrl)) {
            try {
                doReExport(originInvoker, exporter, registryUrl, registeredUrl, newProviderUrl);
            } catch (Exception e) {
                ReExportTask oldTask = reExportFailedTasks.get(registeredUrl);
                if (oldTask != null) {
                    return;
                }
                ReExportTask task = new ReExportTask(
                        () -> doReExport(originInvoker, exporter, registryUrl, registeredUrl, newProviderUrl),
                        registeredUrl,
                        null
                );
                oldTask = reExportFailedTasks.putIfAbsent(registeredUrl, task);
                if (oldTask == null) {
                    // never has a retry task. then start a new task for retry.
                    retryTimer.newTimeout(task, registryUrl.getParameter(REGISTRY_RETRY_PERIOD_KEY, DEFAULT_REGISTRY_RETRY_PERIOD), TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    private <T> void doReExport(final Invoker<T> originInvoker, ExporterChangeableWrapper<T> exporter,
                                URL registryUrl, URL oldProviderUrl, URL newProviderUrl) {
        if (getProviderUrl(originInvoker).getParameter(REGISTER_KEY, true)) {
            Registry registry = null;
            try {
                registry = getRegistry(originInvoker);
            } catch (Exception e) {
                throw new SkipFailbackWrapperException(e);
            }

            logger.info("Try to unregister old url: " + oldProviderUrl);
            registry.reExportUnregister(oldProviderUrl);

            logger.info("Try to register new url: " + newProviderUrl);
            registry.reExportRegister(newProviderUrl);
        }
        try {
            ProviderModel.RegisterStatedURL statedUrl = getStatedUrl(registryUrl, newProviderUrl);
            statedUrl.setProviderUrl(newProviderUrl);
            exporter.setRegisterUrl(newProviderUrl);
        } catch (Exception e) {
            throw new SkipFailbackWrapperException(e);
        }
    }

    private ProviderModel.RegisterStatedURL getStatedUrl(URL registryUrl, URL providerUrl) {
        ProviderModel providerModel = ApplicationModel.getServiceRepository()
                .lookupExportedService(providerUrl.getServiceKey());

        List<ProviderModel.RegisterStatedURL> statedUrls = providerModel.getStatedUrl();
        return statedUrls.stream()
                .filter(u -> u.getRegistryUrl().equals(registryUrl)
                        && u.getProviderUrl().getProtocol().equals(providerUrl.getProtocol()))
                .findFirst().orElseThrow(() -> new IllegalStateException("There should have at least one registered url."));
    }

    /**
     * Get an instance of registry based on the address of invoker
     *
     * @param originInvoker
     * @return
     */
    protected Registry getRegistry(final Invoker<?> originInvoker) {
        URL registryUrl = getRegistryUrl(originInvoker);
        return registryFactory.getRegistry(registryUrl);
    }

    protected URL getRegistryUrl(Invoker<?> originInvoker) {
        URL registryUrl = originInvoker.getUrl();
        if (REGISTRY_PROTOCOL.equals(registryUrl.getProtocol())) {
            String protocol = registryUrl.getParameter(REGISTRY_KEY, DEFAULT_REGISTRY);
            registryUrl = registryUrl.setProtocol(protocol).removeParameter(REGISTRY_KEY);
        }
        return registryUrl;
    }

    protected URL getRegistryUrl(URL url) {
        return URLBuilder.from(url)
                .setProtocol(url.getParameter(REGISTRY_KEY, DEFAULT_REGISTRY))
                .removeParameter(REGISTRY_KEY)
                .build();
    }


    /**
     * Return the url that is registered to the registry and filter the url parameter once
     *
     * @param providerUrl
     * @return url to registry.
     */
    private URL getUrlToRegistry(final URL providerUrl, final URL registryUrl) {
        //The address you see at the registry
        // 如果simplified参数为false
        // 关于这个参数的作用：http://dubbo.apache.org/zh/docs/v2.7/user/examples/simplify-registry-data/
        // 简单来说，是为了减少保存在注册中心的参数数量，将不在服务调用链路上和不在注册中心的核心链路上(服务查询，服务列表)的参数保存到
        // 其他地方，只在注册中心保留必要的参数
        if (!registryUrl.getParameter(SIMPLIFIED_KEY, false)) {
            // 移除providerUrl的一些参数后返回
            return providerUrl.removeParameters(getFilteredKeys(providerUrl)).removeParameters(
                    MONITOR_KEY, BIND_IP_KEY, BIND_PORT_KEY, QOS_ENABLE, QOS_HOST, QOS_PORT, ACCEPT_FOREIGN_IP, VALIDATION_KEY,
                    INTERFACES);
        } else {
            // 如果simplified参数为true，表示需要简化保存在注册中心的参数
            // extra-keys用于配置开启简化后，仍然需要保存到注册中心的参数名
            String extraKeys = registryUrl.getParameter(EXTRA_KEYS_KEY, "");
            // if path is not the same as interface name then we should keep INTERFACE_KEY,
            // otherwise, the registry structure of zookeeper would be '/dubbo/path/providers',
            // but what we expect is '/dubbo/interface/providers'
            // 确保url的path值和interface参数相等，zk会以/dubbo/path/providers作为节点路径，dubbo期望注册中心的节点路径为
            // /dubbo/interface/providers
            if (!providerUrl.getPath().equals(providerUrl.getParameter(INTERFACE_KEY))) {
                if (StringUtils.isNotEmpty(extraKeys)) {
                    extraKeys += ",";
                }
                // 不相等则将interface的值添加到extraKeys
                extraKeys += INTERFACE_KEY;
            }
            // DEFAULT_REGISTER_PROVIDER_KEYS数组指定了默认需要保存在注册中心的参数名称、extraKeys指定了其他需要保存在注册中心的
            // 参数名称
            // getParamsToRegistry方法合并两个数组的值
            String[] paramsToRegistry = getParamsToRegistry(DEFAULT_REGISTER_PROVIDER_KEYS
                    , COMMA_SPLIT_PATTERN.split(extraKeys));
            // 根据providerUrl创建新的url，保留providerUrl中参数名称存在于paramsToRegistry数组中的参数，和以methods参数值开头的参数
            return URL.valueOf(providerUrl, paramsToRegistry, providerUrl.getParameter(METHODS_KEY, (String[]) null));
        }

    }

    private URL getSubscribedOverrideUrl(URL registeredProviderUrl) {
        return registeredProviderUrl.setProtocol(PROVIDER_PROTOCOL)
                .addParameters(CATEGORY_KEY, CONFIGURATORS_CATEGORY, CHECK_KEY, String.valueOf(false));
    }

    /**
     * Get the address of the providerUrl through the url of the invoker
     *
     * @param originInvoker
     * @return
     */
    private URL getProviderUrl(final Invoker<?> originInvoker) {
        String export = originInvoker.getUrl().getParameterAndDecoded(EXPORT_KEY);
        if (export == null || export.length() == 0) {
            throw new IllegalArgumentException("The registry export url is null! registry: " + originInvoker.getUrl());
        }
        return URL.valueOf(export);
    }

    /**
     * Get the key cached in bounds by invoker
     *
     * @param originInvoker
     * @return
     */
    private String getCacheKey(final Invoker<?> originInvoker) {
        // 获取url的export部分
        URL providerUrl = getProviderUrl(originInvoker);
        String key = providerUrl.removeParameters("dynamic", "enabled").toFullString();
        return key;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        // 原url如：
        // registry://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=first-dubbo-consumer&dubbo=2.0.2&pid=65335&refer=application%3Dfirst-dubbo-consumer%26dubbo%3D2.0.2%26group%3Ddubbo%26interface%3Dcom.apache.dubbo.demo.api.GreetingService%26methods%3DsayHello%2CtestGeneric%26pid%3D65335%26register.ip%3D172.19.92.226%26revision%3D1.0.0%26side%3Dconsumer%26sticky%3Dfalse%26timeout%3D5000%26timestamp%3D1612343967799%26version%3D1.0.0&registry=zookeeper&timestamp=1612343967837
        // 修改url的protocol为其registry参数指定的值，如：
        // zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=first-dubbo-consumer&dubbo=2.0.2&pid=65317&refer=application%3Dfirst-dubbo-consumer%26dubbo%3D2.0.2%26group%3Ddubbo%26interface%3Dcom.apache.dubbo.demo.api.GreetingService%26methods%3DsayHello%2CtestGeneric%26pid%3D65317%26register.ip%3D172.19.92.226%26revision%3D1.0.0%26side%3Dconsumer%26sticky%3Dfalse%26timeout%3D5000%26timestamp%3D1612343890983%26version%3D1.0.0&timestamp=1612343894119
        url = getRegistryUrl(url);
        // 获取对应的注册中心实现，如ZookeeperRegistry
        Registry registry = registryFactory.getRegistry(url);
        // 如果当前消费端正在调用的是RegistryService接口的方法，直接根据当前注册中心对象创建invoker即可
        if (RegistryService.class.equals(type)) {
            return proxyFactory.getInvoker((T) registry, type, url);
        }

        // group="a,b" or group="*"
        Map<String, String> qs = StringUtils.parseQueryString(url.getParameterAndDecoded(REFER_KEY));
        String group = qs.get(GROUP_KEY);
        if (group != null && group.length() > 0) {
            if ((COMMA_SPLIT_PATTERN.split(group)).length > 1 || "*".equals(group)) {
                // 如果配置了多个group、或者group为*，则表示需要采用分组聚合的方式调用服务端，具体功能：
                // https://dubbo.apache.org/zh/docs/v2.7/user/examples/group-merger/
                // 这里设置Cluster为MergeableClusterInvoker以实现分组聚合
                return doRefer(Cluster.getCluster(MergeableCluster.NAME), registry, type, url);
            }
        }

        // 获取消费端指定的Cluster，默认为FailoverCluster
        Cluster cluster = Cluster.getCluster(qs.get(CLUSTER_KEY));
        return doRefer(cluster, registry, type, url);
    }

    private <T> Invoker<T> doRefer(Cluster cluster, Registry registry, Class<T> type, URL url) {
        // RegistryDirectory对象保存了消费者的配置和url等信息
        RegistryDirectory<T> directory = new RegistryDirectory<T>(type, url);
        directory.setRegistry(registry);
        directory.setProtocol(protocol);
        // all attributes of REFER_KEY
        Map<String, String> parameters = new HashMap<String, String>(directory.getConsumerUrl().getParameters());
        // 创建订阅url，如：
        // consumer://172.19.92.226/com.apache.dubbo.demo.api.GreetingService?application=first-dubbo-consumer&dubbo=2.0.2&group=dubbo&interface=com.apache.dubbo.demo.api.GreetingService&methods=sayHello,testGeneric&pid=65594&revision=1.0.0&side=consumer&sticky=false&timeout=5000&timestamp=1612345441612&version=1.0.0
        URL subscribeUrl = new URL(CONSUMER_PROTOCOL, parameters.remove(REGISTER_IP_KEY), 0, type.getName(), parameters);
        // 判断消费端是否需要注册到注册中心，默认为true
        if (directory.isShouldRegister()) {
            directory.setRegisteredConsumerUrl(subscribeUrl);
            // 类似服务端注册到注册中心的过程，这里将消费者的url注册到注册中心，如zk会存在如下节点：
            /*
            get /dubbo/com.apache.dubbo.demo.api.GreetingService/consumers/consumer%3A%2F%2F172.19.92.226%2Fcom.apache.dubbo.demo.api.GreetingService%3Fapplication%3Dfirst-dubbo-consumer%26category%3Dconsumers%26check%3Dfalse%26dubbo%3D2.0.2%26group%3Ddubbo%26interface%3Dcom.apache.dubbo.demo.api.GreetingService%26methods%3DsayHello%2CtestGeneric%26pid%3D65875%26revision%3D1.0.0%26side%3Dconsumer%26sticky%3Dfalse%26timeout%3D5000%26timestamp%3D1612345601845%26version%3D1.0.0
            节点值：消费端的ip
             */
            registry.register(directory.getRegisteredConsumerUrl());
        }
        // 初始化RouterChain
        directory.buildRouterChain(subscribeUrl);
        // toSubscribeUrl方法为传入的url加上category参数，参数值为providers,configurators,routers，即需要订阅providers,configurators,routers
        // 这3个配置的变化
        directory.subscribe(toSubscribeUrl(subscribeUrl));
        // cluster默认实现为FailoverCluster，该cluster直接返回FailoverClusterInvoker对象
        Invoker<T> invoker = cluster.join(directory);
        List<RegistryProtocolListener> listeners = findRegistryProtocolListeners(url);
        if (CollectionUtils.isEmpty(listeners)) {
            return invoker;
        }

        // RegistryProtocolListener不为空则遍历调用onRefer方法
        RegistryInvokerWrapper<T> registryInvokerWrapper = new RegistryInvokerWrapper<>(directory, cluster, invoker);
        for (RegistryProtocolListener listener : listeners) {
            listener.onRefer(this, registryInvokerWrapper);
        }
        return registryInvokerWrapper;
    }

    public <T> void reRefer(Invoker<T> invoker, URL newSubscribeUrl) {
        if (!(invoker instanceof RegistryInvokerWrapper)) {
            return;
        }

        RegistryInvokerWrapper<T> invokerWrapper = (RegistryInvokerWrapper<T>) invoker;
        URL oldSubscribeUrl = invokerWrapper.getUrl();
        RegistryDirectory<T> directory = invokerWrapper.getDirectory();
        Registry registry = directory.getRegistry();
        registry.unregister(directory.getRegisteredConsumerUrl());
        directory.unSubscribe(toSubscribeUrl(oldSubscribeUrl));

        directory.setRegisteredConsumerUrl(newSubscribeUrl);
        registry.register(directory.getRegisteredConsumerUrl());
        directory.buildRouterChain(newSubscribeUrl);
        directory.subscribe(toSubscribeUrl(newSubscribeUrl));

        invokerWrapper.setInvoker(invokerWrapper.getCluster().join(directory));
    }

    private static URL toSubscribeUrl(URL url) {
        return url.addParameter(CATEGORY_KEY, PROVIDERS_CATEGORY + "," + CONFIGURATORS_CATEGORY + "," + ROUTERS_CATEGORY);
    }

    private List<RegistryProtocolListener> findRegistryProtocolListeners(URL url) {
        return ExtensionLoader.getExtensionLoader(RegistryProtocolListener.class)
                .getActivateExtension(url, "registry.protocol.listener");
    }

    // available to test
    public String[] getParamsToRegistry(String[] defaultKeys, String[] additionalParameterKeys) {
        int additionalLen = additionalParameterKeys.length;
        String[] registryParams = new String[defaultKeys.length + additionalLen];
        System.arraycopy(defaultKeys, 0, registryParams, 0, defaultKeys.length);
        System.arraycopy(additionalParameterKeys, 0, registryParams, defaultKeys.length, additionalLen);
        return registryParams;
    }

    @Override
    public void destroy() {
        List<RegistryProtocolListener> listeners = ExtensionLoader.getExtensionLoader(RegistryProtocolListener.class)
                .getLoadedExtensionInstances();
        if (CollectionUtils.isNotEmpty(listeners)) {
            for (RegistryProtocolListener listener : listeners) {
                listener.onDestroy();
            }
        }

        List<Exporter<?>> exporters = new ArrayList<Exporter<?>>(bounds.values());
        for (Exporter<?> exporter : exporters) {
            exporter.unexport();
        }
        bounds.clear();

        ExtensionLoader.getExtensionLoader(GovernanceRuleRepository.class).getDefaultExtension()
                .removeListener(ApplicationModel.getApplication() + CONFIGURATORS_SUFFIX, providerConfigurationListener);
    }

    @Override
    public List<ProtocolServer> getServers() {
        return protocol.getServers();
    }

    //Merge the urls of configurators
    private static URL getConfigedInvokerUrl(List<Configurator> configurators, URL url) {
        if (configurators != null && configurators.size() > 0) {
            for (Configurator configurator : configurators) {
                url = configurator.configure(url);
            }
        }
        return url;
    }

    public static class InvokerDelegate<T> extends InvokerWrapper<T> {
        private final Invoker<T> invoker;

        /**
         * @param invoker
         * @param url     invoker.getUrl return this value
         */
        public InvokerDelegate(Invoker<T> invoker, URL url) {
            super(invoker, url);
            this.invoker = invoker;
        }

        public Invoker<T> getInvoker() {
            if (invoker instanceof InvokerDelegate) {
                return ((InvokerDelegate<T>) invoker).getInvoker();
            } else {
                return invoker;
            }
        }
    }

    private static class DestroyableExporter<T> implements Exporter<T> {

        private Exporter<T> exporter;

        public DestroyableExporter(Exporter<T> exporter) {
            this.exporter = exporter;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        @Override
        public void unexport() {
            exporter.unexport();
        }
    }

    /**
     * Reexport: the exporter destroy problem in protocol
     * 1.Ensure that the exporter returned by registryprotocol can be normal destroyed
     * 2.No need to re-register to the registry after notify
     * 3.The invoker passed by the export method , would better to be the invoker of exporter
     */
    //
    private class OverrideListener implements NotifyListener {
        private final URL subscribeUrl;
        private final Invoker originInvoker;


        private List<Configurator> configurators;

        public OverrideListener(URL subscribeUrl, Invoker originalInvoker) {
            this.subscribeUrl = subscribeUrl;
            this.originInvoker = originalInvoker;
        }

        /**
         * @param urls The list of registered information, is always not empty, The meaning is the same as the
         *             return value of {@link org.apache.dubbo.registry.RegistryService#lookup(URL)}.
         */
        @Override
        // 这里的urls参数的值可以看ZookeeperRegistry的doSubscribe方法的实现
        // OverrideListener的主要功能是监听注册中心的/dubbo/serviceInterface/configurators节点，该节点包含动态配置的服务者URL
        // 元数据信息，当该节点的数据发生变化时，OverrideListener获取对应的url并生成一个新的url，再调用RegistryProtocol.this.reExport(originInvoker, newUrl)
        // 重新发布服务
        public synchronized void notify(List<URL> urls) {
            logger.debug("original override urls: " + urls);

            List<URL> matchedUrls = getMatchedUrls(urls, subscribeUrl.addParameter(CATEGORY_KEY,
                    CONFIGURATORS_CATEGORY));
            logger.debug("subscribe url: " + subscribeUrl + ", override urls: " + matchedUrls);

            // No matching results
            if (matchedUrls.isEmpty()) {
                return;
            }

            // isConfigurator方法判断url的protocol是否为override或者参数category的值是否为providers
            this.configurators = Configurator.toConfigurators(classifyUrls(matchedUrls, UrlUtils::isConfigurator))
                    .orElse(configurators);

            doOverrideIfNecessary();
        }

        public synchronized void doOverrideIfNecessary() {
            final Invoker<?> invoker;
            if (originInvoker instanceof InvokerDelegate) {
                invoker = ((InvokerDelegate<?>) originInvoker).getInvoker();
            } else {
                invoker = originInvoker;
            }
            //The origin invoker
            URL originUrl = RegistryProtocol.this.getProviderUrl(invoker);
            String key = getCacheKey(originInvoker);
            ExporterChangeableWrapper<?> exporter = bounds.get(key);
            if (exporter == null) {
                logger.warn(new IllegalStateException("error state, exporter should not be null"));
                return;
            }
            //The current, may have been merged many times
            URL currentUrl = exporter.getInvoker().getUrl();
            //Merged with this configuration
            // 遍历Configurator对象生成新的服务端url，底层的实现很简单，将从注册中心同步到的url中的参数覆盖到当前服务端的url，
            // 实现分别在AbsentConfigurator和OverrideConfigurator
            URL newUrl = getConfigedInvokerUrl(configurators, currentUrl);
            newUrl = getConfigedInvokerUrl(providerConfigurationListener.getConfigurators(), newUrl);
            newUrl = getConfigedInvokerUrl(serviceConfigurationListeners.get(originUrl.getServiceKey())
                    .getConfigurators(), newUrl);
            if (!currentUrl.equals(newUrl)) {
                // 重新export服务端
                RegistryProtocol.this.reExport(originInvoker, newUrl);
                logger.info("exported provider url changed, origin url: " + originUrl +
                        ", old export url: " + currentUrl + ", new export url: " + newUrl);
            }
        }

        private List<URL> getMatchedUrls(List<URL> configuratorUrls, URL currentSubscribe) {
            List<URL> result = new ArrayList<URL>();
            for (URL url : configuratorUrls) {
                URL overrideUrl = url;
                // Compatible with the old version
                if (url.getParameter(CATEGORY_KEY) == null && OVERRIDE_PROTOCOL.equals(url.getProtocol())) {
                    overrideUrl = url.addParameter(CATEGORY_KEY, CONFIGURATORS_CATEGORY);
                }

                // Check whether url is to be applied to the current service
                if (UrlUtils.isMatch(currentSubscribe, overrideUrl)) {
                    result.add(url);
                }
            }
            return result;
        }
    }

    private class ServiceConfigurationListener extends AbstractConfiguratorListener {
        private URL providerUrl;
        private OverrideListener notifyListener;

        public ServiceConfigurationListener(URL providerUrl, OverrideListener notifyListener) {
            this.providerUrl = providerUrl;
            this.notifyListener = notifyListener;
            this.initWith(DynamicConfiguration.getRuleKey(providerUrl) + CONFIGURATORS_SUFFIX);
        }

        private <T> URL overrideUrl(URL providerUrl) {
            return RegistryProtocol.getConfigedInvokerUrl(configurators, providerUrl);
        }

        @Override
        protected void notifyOverrides() {
            notifyListener.doOverrideIfNecessary();
        }
    }

    private class ProviderConfigurationListener extends AbstractConfiguratorListener {

        public ProviderConfigurationListener() {
            this.initWith(ApplicationModel.getApplication() + CONFIGURATORS_SUFFIX);
        }

        /**
         * Get existing configuration rule and override provider url before exporting.
         *
         * @param providerUrl
         * @param <T>
         * @return
         */
        private <T> URL overrideUrl(URL providerUrl) {
            return RegistryProtocol.getConfigedInvokerUrl(configurators, providerUrl);
        }

        @Override
        protected void notifyOverrides() {
            overrideListeners.values().forEach(listener -> ((OverrideListener) listener).doOverrideIfNecessary());
        }
    }

    /**
     * exporter proxy, establish the corresponding relationship between the returned exporter and the exporter
     * exported by the protocol, and can modify the relationship at the time of override.
     *
     * @param <T>
     */
    private class ExporterChangeableWrapper<T> implements Exporter<T> {

        private final ExecutorService executor = newSingleThreadExecutor(new NamedThreadFactory("Exporter-Unexport", true));

        private final Invoker<T> originInvoker;
        private Exporter<T> exporter;
        private URL subscribeUrl;
        private URL registerUrl;

        // exporter为Protocol的export方法返回的，exporter持有的invoker是在原始invoker之外封装了一些wrapper，如InvokerDelegate，
        // ListenerExporterWrapper（该类是ProtocolListenerWrapper封装的）等
        // 而参数originInvoker为最原始的invoker（实际上是DelegateProviderMetaDataInvoker，在ServiceConfig的doExportUrlsFor1Protocol
        // 方法调用PROTOCOL.export之前有一次封装）
        public ExporterChangeableWrapper(Exporter<T> exporter, Invoker<T> originInvoker) {
            this.exporter = exporter;
            this.originInvoker = originInvoker;
        }

        public Invoker<T> getOriginInvoker() {
            return originInvoker;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        public void setExporter(Exporter<T> exporter) {
            this.exporter = exporter;
        }

        @Override
        public void unexport() {
            String key = getCacheKey(this.originInvoker);
            bounds.remove(key);

            // 根据url获取对应的Registry
            Registry registry = RegistryProtocol.this.getRegistry(originInvoker);
            try {
                // 注销服务
                registry.unregister(registerUrl);
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
            try {
                NotifyListener listener = RegistryProtocol.this.overrideListeners.remove(subscribeUrl);
                registry.unsubscribe(subscribeUrl, listener);
                ExtensionLoader.getExtensionLoader(GovernanceRuleRepository.class).getDefaultExtension()
                        .removeListener(subscribeUrl.getServiceKey() + CONFIGURATORS_SUFFIX,
                                serviceConfigurationListeners.get(subscribeUrl.getServiceKey()));
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }

            executor.submit(() -> {
                try {
                    // 在指定的时间内等待consumer执行unexport
                    int timeout = ConfigurationUtils.getServerShutdownTimeout();
                    if (timeout > 0) {
                        logger.info("Waiting " + timeout + "ms for registry to notify all consumers before unexport. " +
                                "Usually, this is called when you use dubbo API");
                        Thread.sleep(timeout);
                    }
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            });
        }

        public void setSubscribeUrl(URL subscribeUrl) {
            this.subscribeUrl = subscribeUrl;
        }

        public void setRegisterUrl(URL registerUrl) {
            this.registerUrl = registerUrl;
        }

        public URL getRegisterUrl() {
            return registerUrl;
        }
    }

    // for unit test
    private static RegistryProtocol INSTANCE;

    // for unit test
    public RegistryProtocol() {
        INSTANCE = this;
    }

    // for unit test
    public static RegistryProtocol getRegistryProtocol() {
        if (INSTANCE == null) {
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(REGISTRY_PROTOCOL); // load
        }
        return INSTANCE;
    }
}
