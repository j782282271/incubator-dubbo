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
package com.alibaba.dubbo.rpc.cluster.configurator;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.cluster.Configurator;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * AbstractOverrideConfigurator
 */
public abstract class AbstractConfigurator implements Configurator {

    //config目录下的url
    private final URL configuratorUrl;

    public AbstractConfigurator(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("configurator url == null");
        }
        this.configuratorUrl = url;
    }

    @Override
    public URL getUrl() {
        return configuratorUrl;
    }

    /**
     * 1）configuratorUrl为config目录下的配置的url：
     * 如：dubbo-all文件夹下该文件内容：dubbo admin操作.txt
     * 2）参数url:
     * for provider；originInvoker.url（含side=provider），即provider初始创建的url（每次都是），见：RegistryProtocol.notify方法
     * for consumer：RegistryDirectory.directoryUrl（ip port为zk的，interface为com.alibaba.dubbo.registry.RegistryService）
     * **************见RegistryDirectory.notify方法
     */
    @Override
    public URL configure(URL url) {
        if (configuratorUrl == null || configuratorUrl.getHost() == null
                || url == null || url.getHost() == null) {
            return url;
        }

        if (configuratorUrl.getPort() != 0) {
            //存在port说明configuratorUrl为provider的
            //想要通过configuratorUrl仅仅控制provider端的行为,
            //主要想控制指定的provider实例或者对consumer隐藏该provider
            if (url.getPort() == configuratorUrl.getPort()) {
                //如果是provider，则只关心config目录的变化（记录到configuratorUrl），不关心其他目录的变化。
                //此处传进来的url为为originInvoker.url，见：RegistryProtocol.notify方法，此处与RegistryProtocol.notify有点耦合
                //url(originInvoker.url即provider要暴露的原始url)与configuratorUrl host匹配，则使用configuratorUrl配置url
                return configureIfMatch(url.getHost(), url);
            }
        } else {
            //override url无port，说明是consumer地址或者是0.0.0.0
            if (url.getParameter(Constants.SIDE_KEY, Constants.PROVIDER).equals(Constants.CONSUMER)) {
                //如果是consumer地址，则是为了控制某一个consumer实例，所以要在指定的该实例端处理，provider和其他consumer应该不受影响
                //本机与configuratorUrl匹配，则使用configuratorUrl配置url
                //consumer关心的是configurators（体现为configuratorUrl）, routers, providers这三个目录的变化,后两个目录变化的url不会进入到这里
                //consumer能进入到此处的url，只是RegistryDirectory.directoryUrl即consumer注册zk的zk ip:port+一些consumer参数（见RegistryDirectory.notify方法，耦合）
                //RegistryDirectory.directoryUrl其中没记录consumer的ip，所以需要用getLocalHost(),匹配与config目录的url是否匹配
                return configureIfMatch(NetUtils.getLocalHost(), url);// NetUtils.getLocalHost is the ip address consumer registered to registry.
            } else if (url.getParameter(Constants.SIDE_KEY, Constants.CONSUMER).equals(Constants.PROVIDER)) {
                //如果port不存在且是0.0.0.0且是provider
                //0.0.0.0与configuratorUrl匹配，则使用configuratorUrl配置url
                return configureIfMatch(Constants.ANYHOST_VALUE, url);// take effect on all providers, so address must be 0.0.0.0, otherwise it won't flow to this if branch
            }
        }
        return url;
    }

    private URL configureIfMatch(String host, URL url) {
        if (Constants.ANYHOST_VALUE.equals(configuratorUrl.getHost()) || host.equals(configuratorUrl.getHost())) {
            String configApplication = configuratorUrl.getParameter(Constants.APPLICATION_KEY, configuratorUrl.getUsername());
            String currentApplication = url.getParameter(Constants.APPLICATION_KEY, url.getUsername());
            //app相同
            if (configApplication == null || Constants.ANY_VALUE.equals(configApplication) || configApplication.equals(currentApplication)) {
                Set<String> conditionKeys = new HashSet<String>();
                // 配置 URL 中的条件 KEYS 集合。其中下面四个 KEY ，不算是条件，而是内置属性。考虑到下面要移除，所以添加到该集合中
                conditionKeys.add(Constants.CATEGORY_KEY);
                conditionKeys.add(Constants.CHECK_KEY);
                conditionKeys.add(Constants.DYNAMIC_KEY);
                conditionKeys.add(Constants.ENABLED_KEY);
                for (Map.Entry<String, String> entry : configuratorUrl.getParameters().entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    // 除了 "application" 和 "side" 之外，带有 `"~"` 开头的 KEY ，也是条件。
                    if (key.startsWith("~") || Constants.APPLICATION_KEY.equals(key) || Constants.SIDE_KEY.equals(key)) {
                        conditionKeys.add(key);
                        if (value != null && !Constants.ANY_VALUE.equals(value)
                                && !value.equals(url.getParameter(key.startsWith("~") ? key.substring(1) : key))) {
                            return url;
                        }
                    }
                }
                return doConfigure(url, configuratorUrl.removeParameters(conditionKeys));
            }
        }
        return url;
    }

    /**
     * Sort by host, priority
     * 1. the url with a specific host ip should have higher priority than 0.0.0.0
     * 2. if two url has the same host, compare by priority value；
     *
     * @param o
     * @return
     */
    @Override
    public int compareTo(Configurator o) {
        if (o == null) {
            return -1;
        }

        int ipCompare = getUrl().getHost().compareTo(o.getUrl().getHost());
        if (ipCompare == 0) {//host is the same, sort by priority
            int i = getUrl().getParameter(Constants.PRIORITY_KEY, 0),
                    j = o.getUrl().getParameter(Constants.PRIORITY_KEY, 0);
            return i < j ? -1 : (i == j ? 0 : 1);
        } else {
            return ipCompare;
        }


    }

    protected abstract URL doConfigure(URL currentUrl, URL configUrl);

}
