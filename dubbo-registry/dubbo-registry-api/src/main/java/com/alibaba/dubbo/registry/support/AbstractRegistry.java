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
package com.alibaba.dubbo.registry.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.Registry;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AbstractRegistry. (SPI, Prototype, ThreadSafe)
 * 功能1：定时刷缓存urls到文件、从文件中读urls
 * 功能2：查找满足条件的urls，接收zk变化通知
 * 功能3：注册url变化的listener
 * 功能4：保存已经注册的urls集合
 */
public abstract class AbstractRegistry implements Registry {

    // URL地址分隔符，用于文件缓存中，服务提供者URL分隔
    private static final char URL_SEPARATOR = ' ';
    // URL地址分隔正则表达式，用于解析文件缓存中服务提供者URL列表
    private static final String URL_SPLIT = "\\s+";
    // Log output
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    //本地磁盘缓存，其中特殊的key值：registies 记录注册中心列表
    //其它均为notified服务提供者列表
    private final Properties properties = new Properties();
    // 文件缓存定时写入
    private final ExecutorService registryCacheExecutor = Executors.newFixedThreadPool(1, new NamedThreadFactory("DubboSaveRegistryCache", true));
    //是否同步保存磁盘
    private final boolean syncSaveFile;
    //记录更新磁盘缓存配置的版本，防并发先来的的覆盖后来的
    private final AtomicLong lastCacheChanged = new AtomicLong();
    //已注册过的url集合
    private final Set<URL> registered = new ConcurrentHashSet<URL>();
    //监听category（包括config/route）目录下的的url配置变化情况
    private final ConcurrentMap<URL, Set<NotifyListener>> subscribed = new ConcurrentHashMap<URL, Set<NotifyListener>>();
    //category（包括config/route）目录下的的url配置变化信息存储在内存notified中
    //key为subscribed中的key，不是通知过来的key,通知过来的key如果与subscribed的key matched，则通知subscribed的listener,通知过后放到这里
    private final ConcurrentMap<URL, Map<String/**category*/, List<URL>>> notified = new ConcurrentHashMap<URL, Map<String, List<URL>>>();
    //zookeeper://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService，可能含一些密码信息
    private URL registryUrl;
    //本地磁盘缓存文件
    private File file;

    public AbstractRegistry(URL url) {
        setUrl(url);
        // Start file save timer
        syncSaveFile = url.getParameter(Constants.REGISTRY_FILESAVE_SYNC_KEY, false);
        String filename = url.getParameter(Constants.FILE_KEY, System.getProperty("user.home") + "/.dubbo/dubbo-registry-" + url.getParameter(Constants.APPLICATION_KEY) + "-" + url.getAddress() + ".cache");
        File file = null;
        if (ConfigUtils.isNotEmpty(filename)) {
            file = new File(filename);
            if (!file.exists() && file.getParentFile() != null && !file.getParentFile().exists()) {
                if (!file.getParentFile().mkdirs()) {
                    throw new IllegalArgumentException("Invalid registry store file " + file + ", cause: Failed to create directory " + file.getParentFile() + "!");
                }
            }
        }
        this.file = file;
        loadProperties();
        notify(url.getBackupUrls());
    }

    /**
     * 如果参数urls为空，则设置url的协议为empty,并返回
     */
    protected static List<URL> filterEmpty(URL url, List<URL> urls) {
        if (urls == null || urls.isEmpty()) {
            List<URL> result = new ArrayList<URL>(1);
            result.add(url.setProtocol(Constants.EMPTY_PROTOCOL));
            return result;
        }
        return urls;
    }

    @Override
    public URL getUrl() {
        return registryUrl;
    }

    protected void setUrl(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("registry url == null");
        }
        this.registryUrl = url;
    }

    public Set<URL> getRegistered() {
        return registered;
    }

    public Map<URL, Set<NotifyListener>> getSubscribed() {
        return subscribed;
    }

    public Map<URL, Map<String, List<URL>>> getNotified() {
        return notified;
    }

    public File getCacheFile() {
        return file;
    }

    public Properties getCacheProperties() {
        return properties;
    }

    public AtomicLong getLastCacheChanged() {
        return lastCacheChanged;
    }

    /**
     * properties保存到文件
     */
    public void doSaveProperties(long version) {
        if (version < lastCacheChanged.get()) {
            return;
        }
        if (file == null) {
            return;
        }
        // Save
        try {
            File lockfile = new File(file.getAbsolutePath() + ".lock");
            if (!lockfile.exists()) {
                lockfile.createNewFile();
            }
            RandomAccessFile raf = new RandomAccessFile(lockfile, "rw");
            try {
                FileChannel channel = raf.getChannel();
                try {
                    FileLock lock = channel.tryLock();
                    if (lock == null) {
                        throw new IOException("Can not lock the registry cache file " + file.getAbsolutePath() + ", ignore and retry later, maybe multi java process use the file, please config: dubbo.registry.file=xxx.properties");
                    }
                    // Save
                    try {
                        if (!file.exists()) {
                            file.createNewFile();
                        }
                        FileOutputStream outputFile = new FileOutputStream(file);
                        try {
                            properties.store(outputFile, "Dubbo Registry Cache");
                        } finally {
                            outputFile.close();
                        }
                    } finally {
                        lock.release();
                    }
                } finally {
                    channel.close();
                }
            } finally {
                raf.close();
            }
        } catch (Throwable e) {
            if (version < lastCacheChanged.get()) {
                return;
            } else {
                registryCacheExecutor.execute(new SaveProperties(lastCacheChanged.incrementAndGet()));
            }
            logger.warn("Failed to save registry store file, cause: " + e.getMessage(), e);
        }
    }

    /**
     * 文件-->properties
     */
    private void loadProperties() {
        if (file != null && file.exists()) {
            InputStream in = null;
            try {
                in = new FileInputStream(file);
                properties.load(in);
                if (logger.isInfoEnabled()) {
                    logger.info("Load registry store file " + file + ", data: " + properties);
                }
            } catch (Throwable e) {
                logger.warn("Failed to load registry store file " + file, e);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            }
        }
    }

    /**
     * properties格式：
     * serviceKey1-->url1 url2 url3...
     * serviceKey2-->url4...
     */
    public List<URL> getCacheUrls(URL url) {
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();
            if (key != null && key.length() > 0 && key.equals(url.getServiceKey())
                    && (Character.isLetter(key.charAt(0)) || key.charAt(0) == '_')
                    && value != null && value.length() > 0) {
                String[] arr = value.trim().split(URL_SPLIT);
                List<URL> urls = new ArrayList<URL>();
                for (String u : arr) {
                    urls.add(URL.valueOf(u));
                }
                return urls;
            }
        }
        return null;
    }

    /**
     * 根据url从notified map中查找满足条件的url集合
     * 如果没找到满足条件的urls集合，则阻塞等待，一直到有为止
     */
    @Override
    public List<URL> lookup(URL url) {
        List<URL> result = new ArrayList<URL>();
        //url必须与key中的每个参数都相同才行，
        //注意getNotified中的key也是subscribe中的key,notify一些urls，这些urls经过与subscribe中的key匹配UrlUtils.isMatch后，
        //如果匹配上了会通知，通知后会放在notified这个map中，其中的key就是subscribe中的key
        Map<String, List<URL>> notifiedUrls = getNotified().get(url);
        if (notifiedUrls != null && notifiedUrls.size() > 0) {
            for (List<URL> urls : notifiedUrls.values()) {
                for (URL u : urls) {
                    if (!Constants.EMPTY_PROTOCOL.equals(u.getProtocol())) {
                        result.add(u);
                    }
                }
            }
        } else {
            final AtomicReference<List<URL>> reference = new AtomicReference<List<URL>>();
            NotifyListener listener = new NotifyListener() {
                @Override
                public void notify(List<URL> urls) {
                    reference.set(urls);
                }
            };
            subscribe(url, listener); // Subscribe logic guarantees the first notify to return
            List<URL> urls = reference.get();
            if (urls != null && !urls.isEmpty()) {
                for (URL u : urls) {
                    if (!Constants.EMPTY_PROTOCOL.equals(u.getProtocol())) {
                        result.add(u);
                    }
                }
            }
        }
        return result;
    }

    /**
     * 向registered 这个set中加入url
     */
    @Override
    public void register(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("register url == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Register: " + url);
        }
        registered.add(url);
    }

    /**
     * 从registered 这个set中删除url
     */
    @Override
    public void unregister(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("unregister url == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Unregister: " + url);
        }
        registered.remove(url);
    }

    /**
     * 向subscribed这个map中加入url，对应的listener,一个url可以有多个listener
     */
    @Override
    public void subscribe(URL url, NotifyListener listener) {
        if (url == null) {
            throw new IllegalArgumentException("subscribe url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("subscribe listener == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Subscribe: " + url);
        }
        Set<NotifyListener> listeners = subscribed.get(url);
        if (listeners == null) {
            subscribed.putIfAbsent(url, new ConcurrentHashSet<NotifyListener>());
            listeners = subscribed.get(url);
        }
        listeners.add(listener);
    }

    @Override
    public void unsubscribe(URL url, NotifyListener listener) {
        if (url == null) {
            throw new IllegalArgumentException("unsubscribe url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("unsubscribe listener == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Unsubscribe: " + url);
        }
        Set<NotifyListener> listeners = subscribed.get(url);
        if (listeners != null) {
            listeners.remove(listener);
        }
    }

    /**
     * 从registered这个set中取出已注册的url,重新注册
     * 从subscribed这个map中取出已订阅的listener,重新订阅
     */
    protected void recover() throws Exception {
        // register
        Set<URL> recoverRegistered = new HashSet<URL>(getRegistered());
        if (!recoverRegistered.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover register url " + recoverRegistered);
            }
            for (URL url : recoverRegistered) {
                register(url);
            }
        }
        // subscribe
        Map<URL, Set<NotifyListener>> recoverSubscribed = new HashMap<URL, Set<NotifyListener>>(getSubscribed());
        if (!recoverSubscribed.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover subscribe url " + recoverSubscribed.keySet());
            }
            for (Map.Entry<URL, Set<NotifyListener>> entry : recoverSubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    subscribe(url, listener);
                }
            }
        }
    }

    /**
     * 遍历已订阅的url和listener，寻找满足参数urls的url，通知其listener
     */
    protected void notify(List<URL> urls) {
        if (urls == null || urls.isEmpty()) return;

        //遍历订阅者
        for (Map.Entry<URL, Set<NotifyListener>> entry : getSubscribed().entrySet()) {
            URL url = entry.getKey();
            //与第一个url匹配则通知
            if (!UrlUtils.isMatch(url, urls.get(0))) {
                continue;
            }

            Set<NotifyListener> listeners = entry.getValue();
            if (listeners != null) {
                for (NotifyListener listener : listeners) {
                    try {
                        notify(url, listener, filterEmpty(url, urls));
                    } catch (Throwable t) {
                        logger.error("Failed to notify registry event, urls: " + urls + ", cause: " + t.getMessage(), t);
                    }
                }
            }
        }
    }

    /**
     * url: subscribe的key
     * listener：url对应的listener
     * urls：通知过来的urls
     * 将urls按照category分类，放入已通知的notified map中
     * 通知listener，保存变化到本地文件中
     */
    protected void notify(URL url, NotifyListener listener, List<URL> urls) {
        if (url == null) {
            throw new IllegalArgumentException("notify url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("notify listener == null");
        }
        if ((urls == null || urls.isEmpty())
                && !Constants.ANY_VALUE.equals(url.getServiceInterface())) {
            logger.warn("Ignore empty notify urls for subscribe url " + url);
            return;
        }
        if (logger.isInfoEnabled()) {
            logger.info("Notify urls for subscribe url " + url + ", urls: " + urls);
        }
        //将urls(通知过来的urls集合)中,满足条件的url，按照category分类
        Map<String, List<URL>> result = new HashMap<String, List<URL>>();
        for (URL u : urls) {
            if (UrlUtils.isMatch(url, u)) {
                String category = u.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY);
                List<URL> categoryList = result.get(category);
                if (categoryList == null) {
                    categoryList = new ArrayList<URL>();
                    result.put(category, categoryList);
                }
                categoryList.add(u);
            }
        }
        if (result.size() == 0) {
            return;
        }
        //已经通知过的url集合
        Map<String, List<URL>> categoryNotified = notified.get(url);
        if (categoryNotified == null) {
            notified.putIfAbsent(url, new ConcurrentHashMap<String, List<URL>>());
            categoryNotified = notified.get(url);
        }
        //将通知过来的url集合放入已通知的的map中，并通知、保存本地文件
        for (Map.Entry<String, List<URL>> entry : result.entrySet()) {
            String category = entry.getKey();
            List<URL> categoryList = entry.getValue();
            categoryNotified.put(category, categoryList);
            saveProperties(url);
            listener.notify(categoryList);
        }
    }

    /**
     * ConcurrentMap<URL, Map<String,List<URL>>>notified 找到对应url下的
     * values:Map<String,List<URL>,其中的values:List<URL> 将其url之间用空格分割，作为value.
     * url.getServiceKey作为key,存储到文件
     */
    private void saveProperties(URL url) {
        if (file == null) {
            return;
        }

        try {
            StringBuilder buf = new StringBuilder();
            Map<String, List<URL>> categoryNotified = notified.get(url);
            if (categoryNotified != null) {
                for (List<URL> us : categoryNotified.values()) {
                    for (URL u : us) {
                        if (buf.length() > 0) {
                            buf.append(URL_SEPARATOR);
                        }
                        buf.append(u.toFullString());
                    }
                }
            }
            properties.setProperty(url.getServiceKey(), buf.toString());
            long version = lastCacheChanged.incrementAndGet();
            if (syncSaveFile) {
                doSaveProperties(version);
            } else {
                registryCacheExecutor.execute(new SaveProperties(version));
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    @Override
    public void destroy() {
        if (logger.isInfoEnabled()) {
            logger.info("Destroy registry:" + getUrl());
        }
        Set<URL> destroyRegistered = new HashSet<URL>(getRegistered());
        if (!destroyRegistered.isEmpty()) {
            for (URL url : new HashSet<URL>(getRegistered())) {
                if (url.getParameter(Constants.DYNAMIC_KEY, true)) {
                    try {
                        unregister(url);
                        if (logger.isInfoEnabled()) {
                            logger.info("Destroy unregister url " + url);
                        }
                    } catch (Throwable t) {
                        logger.warn("Failed to unregister url " + url + " to registry " + getUrl() + " on destroy, cause: " + t.getMessage(), t);
                    }
                }
            }
        }
        Map<URL, Set<NotifyListener>> destroySubscribed = new HashMap<URL, Set<NotifyListener>>(getSubscribed());
        if (!destroySubscribed.isEmpty()) {
            for (Map.Entry<URL, Set<NotifyListener>> entry : destroySubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    try {
                        unsubscribe(url, listener);
                        if (logger.isInfoEnabled()) {
                            logger.info("Destroy unsubscribe url " + url);
                        }
                    } catch (Throwable t) {
                        logger.warn("Failed to unsubscribe url " + url + " to registry " + getUrl() + " on destroy, cause: " + t.getMessage(), t);
                    }
                }
            }
        }
    }

    @Override
    public String toString() {
        return getUrl().toString();
    }

    private class SaveProperties implements Runnable {
        private long version;

        private SaveProperties(long version) {
            this.version = version;
        }

        @Override
        public void run() {
            doSaveProperties(version);
        }
    }

}
