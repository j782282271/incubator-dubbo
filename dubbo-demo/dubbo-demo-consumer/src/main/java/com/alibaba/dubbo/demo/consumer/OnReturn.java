package com.alibaba.dubbo.demo.consumer;

/**
 * Created by Administrator on 2019/4/6.
 */
public class OnReturn {
    public void onreturn(String res, String req) {
        System.out.println("req=" + req + " res=" + res);
    }
}
