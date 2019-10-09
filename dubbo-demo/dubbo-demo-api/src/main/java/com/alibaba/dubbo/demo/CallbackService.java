package com.alibaba.dubbo.demo;

/**
 * Created by Administrator on 2019/4/6.
 */
public interface CallbackService {
    void addListener(String key, CallbackListener listener);
}
