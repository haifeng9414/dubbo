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
package org.apache.dubbo.rpc.proxy.javassist;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.bytecode.Proxy;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.proxy.AbstractProxyFactory;
import org.apache.dubbo.rpc.proxy.AbstractProxyInvoker;
import org.apache.dubbo.rpc.proxy.InvokerInvocationHandler;

/**
 * JavassistRpcProxyFactory
 */
public class JavassistProxyFactory extends AbstractProxyFactory {

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProxy(Invoker<T> invoker, Class<?>[] interfaces) {
        /*
         可以通过在javassist的ClassGenerator类的toClass方法返回前，调用mCtc.writeFile("/Users/donghaifeng/tmp")获取动态生成的
         类定义，生成的类例子：
        // Source code recreated from a .class file by IntelliJ IDEA
        // (powered by Fernflower decompiler)
        //

        package org.apache.dubbo.common.bytecode;

        import com.alibaba.dubbo.rpc.service.EchoService;
        import com.apache.dubbo.demo.api.GreetingService;
        import com.apache.dubbo.demo.api.PoJo;
        import com.apache.dubbo.demo.api.Result;
        import java.lang.reflect.InvocationHandler;
        import java.lang.reflect.Method;
        import org.apache.dubbo.common.bytecode.ClassGenerator.DC;
        import org.apache.dubbo.rpc.service.Destroyable;

        public class proxy0 implements DC, GreetingService, Destroyable, EchoService {
            public static Method[] methods;
            private InvocationHandler handler;

            // testGeneric和sayHello方法都是GreetingService接口中的
            public Result testGeneric(PoJo var1) {
                Object[] var2 = new Object[]{var1};
                Object var3 = this.handler.invoke(this, methods[0], var2);
                return (Result)var3;
            }

            public String sayHello(String var1) {
                Object[] var2 = new Object[]{var1};
                Object var3 = this.handler.invoke(this, methods[1], var2);
                return (String)var3;
            }

            public Object $echo(Object var1) {
                Object[] var2 = new Object[]{var1};
                Object var3 = this.handler.invoke(this, methods[2], var2);
                return (Object)var3;
            }

            public void $destroy() {
                Object[] var1 = new Object[0];
                this.handler.invoke(this, methods[3], var1);
            }

            public proxy0() {
            }

            public proxy0(InvocationHandler var1) {
                this.handler = var1;
            }
        }
         */
        final Proxy proxy = Proxy.getProxy(interfaces);
        // 可以看到上面生成的类构造函数需要传入InvokerInvocationHandler对象
        return (T) proxy.newInstance(new InvokerInvocationHandler(invoker));
    }

    @Override
    public <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) {
        // TODO Wrapper cannot handle this scenario correctly: the classname contains '$'
        final Wrapper wrapper = Wrapper.getWrapper(proxy.getClass().getName().indexOf('$') < 0 ? proxy.getClass() : type);
        /*
        反编译动态生成的类可以通过复制idea的启动命令中classpath部分的值，通过执行javap -classpath xxx className实现
        生成的wrapper如下，主要用于避免反射调用的开销，可以看下面的invokeMethod方法
//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package org.apache.dubbo.common.bytecode;

import com.apache.dubbo.demo.api.GreetingService;
import com.apache.dubbo.demo.api.PoJo;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import org.apache.dubbo.common.bytecode.ClassGenerator.DC;

public class Wrapper0 extends Wrapper implements DC {
    public static String[] pns;
    public static Map pts;
    public static String[] mns;
    public static String[] dmns;
    public static Class[] mts0;
    public static Class[] mts1;

    public String[] getPropertyNames() {
        return pns;
    }

    public boolean hasProperty(String var1) {
        return pts.containsKey(var1);
    }

    public Class getPropertyType(String var1) {
        return (Class)pts.get(var1);
    }

    public String[] getMethodNames() {
        return mns;
    }

    public String[] getDeclaredMethodNames() {
        return dmns;
    }

    // GreetingService是个接口，没有属性，所以这里的setPropertyValue和下面的getPropertyValue方法没有什么逻辑
    public void setPropertyValue(Object var1, String var2, Object var3) {
        try {
            GreetingService var4 = (GreetingService)var1;
        } catch (Throwable var6) {
            throw new IllegalArgumentException(var6);
        }

        throw new NoSuchPropertyException("Not found property \"" + var2 + "\" field or setter method in class com.apache.dubbo.demo.api.GreetingService.");
    }

    public Object getPropertyValue(Object var1, String var2) {
        try {
            GreetingService var3 = (GreetingService)var1;
        } catch (Throwable var5) {
            throw new IllegalArgumentException(var5);
        }

        throw new NoSuchPropertyException("Not found property \"" + var2 + "\" field or setter method in class com.apache.dubbo.demo.api.GreetingService.");
    }

    // 强转成GreetingService后调用方法，避免反射调用
    public Object invokeMethod(Object var1, String var2, Class[] var3, Object[] var4) throws InvocationTargetException {
        GreetingService var5;
        try {
            var5 = (GreetingService)var1;
        } catch (Throwable var8) {
            throw new IllegalArgumentException(var8);
        }

        try {
            if ("sayHello".equals(var2) && var3.length == 1) {
                return var5.sayHello((String)var4[0]);
            }

            if ("testGeneric".equals(var2) && var3.length == 1) {
                return var5.testGeneric((PoJo)var4[0]);
            }
        } catch (Throwable var9) {
            throw new InvocationTargetException(var9);
        }

        throw new NoSuchMethodException("Not found method \"" + var2 + "\" in class com.apache.dubbo.demo.api.GreetingService.");
    }

    public Wrapper0() {
    }
}

 */
        // AbstractProxyInvoker封装了将异步调用或同步调用封装为AsyncRpcResult，AsyncRpcResult表示一个异步的执行结果
        return new AbstractProxyInvoker<T>(proxy, type, url) {
            @Override
            protected Object doInvoke(T proxy, String methodName,
                                      Class<?>[] parameterTypes,
                                      Object[] arguments) throws Throwable {
                // 这里的invokeMethod可以理解为调用当前服务端的实现类的方法
                return wrapper.invokeMethod(proxy, methodName, parameterTypes, arguments);
            }
        };
    }

}
