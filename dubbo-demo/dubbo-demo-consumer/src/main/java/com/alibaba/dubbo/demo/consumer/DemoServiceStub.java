package com.alibaba.dubbo.demo.consumer;

import com.alibaba.dubbo.demo.DemoService;

/**
 * Created by Administrator on 2019/4/6.
 */
public class DemoServiceStub implements DemoService {
    private DemoService service;

    public DemoServiceStub(DemoService service) {
        this.service = service;
    }

    @Override
    public String sayHello(String name) {
        System.out.println("stub pre do name=" + name);
        return service.sayHello(name);
    }
}
