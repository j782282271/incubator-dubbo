package com.alibaba.dubbo.demo.provider;

import com.alibaba.dubbo.rpc.service.GenericException;
import com.alibaba.dubbo.rpc.service.GenericService;

/**
 * Created by jiangyang on 2019/4/5.
 */
public class GenService implements GenericService {
    @Override
    public Object $invoke(String method, String[] parameterTypes, Object[] args) throws GenericException {
        if (method.equals("sayHello")) {
            return "hello" + args[0];
        }
        return "null";
    }
}
