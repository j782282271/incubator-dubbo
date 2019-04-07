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
package com.alibaba.dubbo.config;

import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.registry.support.AbstractRegistryFactory;
import com.alibaba.dubbo.rpc.Protocol;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The shutdown hook thread to do the clean up stuff.
 * This is a singleton in order to ensure there is only one shutdown hook registered.
 * Because {@link ApplicationShutdownHooks} use {@link java.util.IdentityHashMap}
 * to store the shutdown hooks.
 */
public class DubboShutdownHook extends Thread {

    private static final Logger logger = LoggerFactory.getLogger(DubboShutdownHook.class);

    private static final DubboShutdownHook dubboShutdownHook = new DubboShutdownHook("DubboShutdownHook");

    public static DubboShutdownHook getDubboShutdownHook() {
        return dubboShutdownHook;
    }

    /**
     * Has it already been destroyed or not?
     */
    private final AtomicBoolean destroyed;

    private DubboShutdownHook(String name) {
        super(name);
        this.destroyed = new AtomicBoolean(false);
    }

    @Override
    public void run() {
        if (logger.isInfoEnabled()) {
            logger.info("Run shutdown hook now.");
        }
        destroyAll();
    }

    /**
     * Destroy all the resources, including registries and protocols.
     */
    public void destroyAll() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        //关闭所有注册，取消注册，这样上游所有调用本服务的consumer就可以自动剔除本服务调用
        //根据注册中心改掉其他服务
        AbstractRegistryFactory.destroyAll();
        // destroy all the protocols
        ExtensionLoader<Protocol> loader = ExtensionLoader.getExtensionLoader(Protocol.class);
        for (String protocolName : loader.getLoadedExtensions()) {
            try {
                //关闭所有协议，以dubboProtocol为例，协议关闭分为两步：
                //1关闭本服务的所有provider，即关闭server,server关闭以HeaderExchangeServer为例，分为以下3步：
                //  1.1）要close，通知连接本server的所有channel一个事件：Request.READONLY_EVENT，client收到该事件后，将channel放置一个Request.READONLY_EVENT
                //Request.READONLY_EVENT属性，存在该属性，client则认为该provider isAvaliable==false
                //  1.2）如果存在channel没关闭且没达到超时间，则等待到超时时间（ConfigUtils.getServerShutdownTimeout）
                //  1.3）停止心跳、关闭所有channel（nettyServer负责）
                //2关闭本服务的所有consumer，即client的channel，当该consumer的channel仍活跃，即有请求未返回，则等待其返回，然后关闭，或者超时强制关闭
                Protocol protocol = loader.getLoadedExtension(protocolName);
                if (protocol != null) {
                    protocol.destroy();
                }
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
        }
    }


}
