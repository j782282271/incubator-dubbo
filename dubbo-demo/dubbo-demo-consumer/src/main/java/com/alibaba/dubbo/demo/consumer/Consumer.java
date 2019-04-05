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
package com.alibaba.dubbo.demo.consumer;

import com.alibaba.dubbo.demo.CallbackListener;
import com.alibaba.dubbo.demo.CallbackService;
import com.alibaba.dubbo.demo.DemoService;
import com.alibaba.dubbo.demo.DemoTestService;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class Consumer {

    public static void main(String[] args) throws Exception {
        //Prevent to get IPV6 address,this way only work in debug mode
        //But you can pass use -Djava.net.preferIPv4Stack=true,then it work well whether in debug mode or not
        System.setProperty("java.net.preferIPv4Stack", "true");
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext(new String[]{"META-INF/spring/dubbo-demo-consumer.xml"});
        context.start();
        DemoService demoService = (DemoService) context.getBean("demoService"); // get remote service proxy
        DemoTestService demoTestService = (DemoTestService) context.getBean("demoTestService"); // get remote service proxy
//        GenericService genService = (GenericService) context.getBean("genService"); // get remote service proxy
        CallbackService callbackService = (CallbackService) context.getBean("callbackService");
        callbackService.addListener("foo.bar", new CallbackListener() {
            public void changed(String msg) {
                System.out.println("callback1:" + msg);
            }
        });
        Thread.sleep(1000 * 100);
        while (true) {
            try {
                Thread.sleep(1000);
//                String hello = demoService.sayHello("world"); // call remote method
//                demoTestService.speak(new ValBean());
//                System.out.println(hello); // get result
//                Object result = genService.$invoke("sayHello", new String[]{"java.lang.String"}, new Object[]{"World"});
//                System.out.println(result); // get result
//                demoTestService.speak(new ValBean());
//                Future<String> future = RpcContext.getContext().getFuture();
//                System.out.println(future.get());
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }

    }
}
