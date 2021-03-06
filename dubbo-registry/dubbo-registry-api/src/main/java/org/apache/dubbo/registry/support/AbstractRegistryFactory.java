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
package org.apache.dubbo.registry.support;

import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.EXPORT_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.RegistryFactory;
import org.apache.dubbo.registry.RegistryService;

/**
 * AbstractRegistryFactory. (SPI, Singleton, ThreadSafe)
 *
 * @see org.apache.dubbo.registry.RegistryFactory
 */
public abstract class AbstractRegistryFactory implements RegistryFactory {

    // Log output
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractRegistryFactory.class);

    // The lock for the acquisition process of the registry
    protected static final ReentrantLock LOCK = new ReentrantLock();

    // Registry Collection Map<RegistryAddress, Registry>
    // key为根据注册中心的URL生成的字符串，value为创建的注册中心对象
    protected static final Map<String, Registry> REGISTRIES = new HashMap<>();

    private static final AtomicBoolean destroyed = new AtomicBoolean(false);

    /**
     * Get all registries
     *
     * @return all registries
     */
    public static Collection<Registry> getRegistries() {
        return Collections.unmodifiableCollection(new LinkedList<>(REGISTRIES.values()));
    }

    public static Registry getRegistry(String key) {
        return REGISTRIES.get(key);
    }

    /**
     * Close all created registries
     */
    public static void destroyAll() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Close all registries " + getRegistries());
        }
        // Lock up the registry shutdown process
        LOCK.lock();
        try {
            for (Registry registry : getRegistries()) {
                try {
                    registry.destroy();
                } catch (Throwable e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
            REGISTRIES.clear();
        } finally {
            // Release the lock
            LOCK.unlock();
        }
    }

    @Override
    public Registry getRegistry(URL url) {
        if (destroyed.get()) {
            LOGGER.warn("All registry instances have been destroyed, failed to fetch any instance. " +
                    "Usually, this means no need to try to do unnecessary redundant resource clearance, all "
                    + "registries has been taken care of.");
            return DEFAULT_NOP_REGISTRY;
        }

        /*
         当启动dubbo-demo/dubbo-demo-api-consumer中的org.apache.dubbo.demo.consumer.Application类时，url传入的如下：
         zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?
         application=dubbo-demo-api-consumer
         &dubbo=2.0.2
         &pid=66822
         &refer=application=dubbo-demo-api-consumer
         &dubbo=2.0.2
         &generic=true
         &interface=org.apache.dubbo.demo.DemoService
         &pid=66822
         &register.ip=192.168.1.10
         &side=consumer
         &sticky=false
         &timestamp=1600003754815
         &timestamp=1600003754824
         */
        url = URLBuilder.from(url)
                .setPath(RegistryService.class.getName()) // 替换path
                .addParameter(INTERFACE_KEY, RegistryService.class.getName()) // 替换interface参数
                .removeParameters(EXPORT_KEY, REFER_KEY) // 移除export和refer参数
                .build();

        /*
        build之后url如下：
        zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?
        application=dubbo-demo-api-consumer
        &dubbo=2.0.2
        &interface=org.apache.dubbo.registry.RegistryService
        &pid=66865
        &timestamp=1600004034820
         */

        // 将url对象转换为字符串作为key，对于上面的例子，key为zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService
        String key = createRegistryCacheKey(url);
        // Lock the registry access process to ensure a single instance of the registry
        LOCK.lock();
        try {

            // 从缓存中获取Registry
            Registry registry = REGISTRIES.get(key);
            if (registry != null) {
                return registry;
            }
            //create registry by spi/ioc
            /*
            createRegistry是个抽象方法，用于创建Registry，通常客户端会引入一个Registry的jar包，如dubbo-registry-zookeeper，
            dubbo-demo/dubbo-demo-api-consumer的pom.xml就有如下配置

            <dependency>
                <groupId>org.apache.dubbo</groupId>
                <artifactId>dubbo-registry-zookeeper</artifactId>
            </dependency>

            而dubbo-registry-zookeeper的dubbo-registry-zookeeper/src/main/resources/META-INF/dubbo/internal/org.apache.dubbo.registry.RegistryFactory
            文件有以下内容
            zookeeper=org.apache.dubbo.registry.zookeeper.ZookeeperRegistryFactory

            所以createRegistry方法的实现由客户端引入什么jar包决定
             */
            registry = createRegistry(url);
            if (registry == null) {
                throw new IllegalStateException("Can not create registry " + url);
            }
            REGISTRIES.put(key, registry);
            return registry;
        } finally {
            // Release the lock
            LOCK.unlock();
        }
    }

    /**
     * Create the key for the registries cache.
     * This method may be override by the sub-class.
     *
     * @param url the registration {@link URL url}
     * @return non-null
     */
    protected String createRegistryCacheKey(URL url) {
        return url.toServiceStringWithoutResolving();
    }

    protected abstract Registry createRegistry(URL url);


    private static Registry DEFAULT_NOP_REGISTRY = new Registry() {
        @Override
        public URL getUrl() {
            return null;
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public void destroy() {

        }

        @Override
        public void register(URL url) {

        }

        @Override
        public void unregister(URL url) {

        }

        @Override
        public void subscribe(URL url, NotifyListener listener) {

        }

        @Override
        public void unsubscribe(URL url, NotifyListener listener) {

        }

        @Override
        public List<URL> lookup(URL url) {
            return null;
        }
    };

    public static void removeDestroyedRegistry(Registry toRm) {
        LOCK.lock();
        try {
            REGISTRIES.entrySet().removeIf(entry -> entry.getValue().equals(toRm));
        } finally {
            LOCK.unlock();
        }
    }

    // for unit test
    public static void clearRegistryNotDestroy() {
        REGISTRIES.clear();
    }

}
