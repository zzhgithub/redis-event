#  超轻量级消息中间件

## 依赖

- redisson
- gson 
- spring

# 使用方法

1. 定义好事件 和 消息的类型
消息类型

```java
@Data
public class EleTestMessage implements Serializable {

    private static final long serialVersionUID = 7820188867627392123L;

    private String message;
}

```

事件枚举

```java
public enum EventEnum implements EventType {
    ELE_TEST_MESSAGE("ele.test.message", EleTestMessage.class);

    String name;
    Class clazz;

    EventEnum(String name, Class clazz) {
        this.name = name;
        this.clazz = clazz;
    }

    @Override
    public String getEventName() {
        return name;
    }

    @Override
    public Class getContentType() {
        return clazz;
    }

    public static List<EventType> listOf() {
        return Arrays.asList(EventEnum.values());
    }
}
```

事件名称应该以`.`作为分割。而不建议使用`@`符号。`@`出现的redis中的key为，工具和其他用户不需要的扩展功能的名称标准。

2. 抛出事件

```java
     @Autowired
        private EventService eventService;

 @RequestMapping(value = "/event/{id}")
    @ResponseBody
    public ResultDto<String> testEvent(@PathVariable("id") Long id){
        EleTestMessage eleTestMessage = new EleTestMessage();
        eleTestMessage.setMessage("Holle ele redis message");
        eventService.publishEvent(EventEnum.ELE_TEST_MESSAGE,eleTestMessage);
        ResultDto<String> resultDto = new ResultDto<>();
        return resultDto.success("success");
    }
```

3. 完成消费类

```java
@Service
public class EleTestCallBack implements EventCallback<EleTestMessage> {
    @Override
    public EventType<EleTestMessage> getSupportEventType() {
        return EventEnum.ELE_TEST_MESSAGE;
    }

    @Override
    public boolean consume(EleTestMessage eleTestMessage) throws Exception {
        System.out.println(eleTestMessage.getMessage().toString());
        return true;
    }
}

```

## 配置方法

```java
@Configuration
public class RedissonConf {

    @Value("${redis.uri}")
    private String redisUri;

    @Bean
    public RedissonClient redissonClient(){
        Config config = new Config();
        config.setThreads(2);
        config.setTransportMode(TransportMode.NIO);
        config.useSingleServer().setAddress(redisUri);
        return Redisson.create(config);
    }


    @Bean
    public EventService eventService(@Autowired List<EventCallback> callbackList){
        EventService service = new EventService();
        service.setEventCallbacks(callbackList);
        service.setEventTypes(EventEnum.listOf());
        return service;
    }
}
```

#### 修改后的配置

```java
package com.baffedu.ele.web.conf.plugins;

import com.baffedu.ele.service.event.EventEnum;
import com.baffedu.event.EventCallback;
import com.baffedu.event.EventService;
import org.redisson.Redisson;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.TransportMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class EventConf {

    @Value("${redis.uri}")
    private String redisUri;

    @Value("${system.name}")
    private String systemName;

    @Value("${redis.password}")
    private String redisPassWord;

    @Autowired
    private AfterCommitExecutor afterCommitExecutor;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.setThreads(8);
        config.setTransportMode(TransportMode.NIO);
        config.setNettyThreads(8);

        config.useSingleServer()
                .setConnectionPoolSize(64)
                .setConnectionMinimumIdleSize(64)
                .setRetryAttempts(3)
                .setRetryInterval(1500)
                .setSubscriptionConnectionMinimumIdleSize(50)
                .setSubscriptionConnectionPoolSize(50)
                .setTcpNoDelay(true)
                .setKeepAlive(true)
                .setPingConnectionInterval(60)
                .setAddress(redisUri)
                .setPassword("".equals(redisPassWord) ? null : redisPassWord);
        return Redisson.create(config);
    }


    @Bean
    public EventService eventService(@Autowired List<EventCallback> callbackList) {
        EventService service = new EventService();
        service.setEventCallbacks(callbackList);
        service.setEventTypes(EventEnum.listOf());
        service.setSystemName(systemName);
        service.setExecutor(afterCommitExecutor);
        return service;
    }
}

```


## 怎么处理事务相关的问题？

想象一下如果出现这种请问。我一段代码中包含数据库事务。要commit后才真正在数据库中插入一条数据。可是
此时抛出了事件并且使用这个id进行了查询。（或者插入另外一个表而且还是外键）啊哦。你会发现发生了报错。

这里我们提供了一个Executor让用户控制发生事件时的行为。也就是说你可以控制他在是否在事务commit后发送。
这里提供了spring框架中的使用方法。
```java
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 控制事务提交执行
 */
@Component
public class AfterCommitExecutor extends TransactionSynchronizationAdapter implements Executor {

    private static final Log log = LogFactory.getLog(AfterCommitExecutor.class);
    private static final ThreadLocal<List<Runnable>> RUNNABLES = new ThreadLocal<List<Runnable>>();
    private ExecutorService threadPool = Executors.newFixedThreadPool(5);

    @Override
    public void execute(Runnable runnable) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runnable.run();
            return;
        }
        List<Runnable> threadRunnables = RUNNABLES.get();
        if (threadRunnables == null) {
            threadRunnables = new ArrayList<Runnable>();
            RUNNABLES.set(threadRunnables);
            TransactionSynchronizationManager.registerSynchronization(this);
        }
        threadRunnables.add(runnable);
    }

    @Override
    public void afterCommit() {
        List<Runnable> threadRunnables = RUNNABLES.get();
        for (int i = 0; i < threadRunnables.size(); i++) {
            Runnable runnable = threadRunnables.get(i);
            try {
                threadPool.execute(runnable);
            } catch (RuntimeException e) {
                e.printStackTrace();
                log.error(e.getMessage());
            }
        }
    }

    @Override
    public void afterCompletion(int status) {
        RUNNABLES.remove();
    }
}

```
然后把这个类传到EventService中就好。

## 运行一段时间后出现下面的问题怎么办?
```log
Exception in thread "EventConsume-ELE_TEST_MESSAGE" Exception in thread "EventConsume-ELE_REG_ADMIN_EVENT" Exception in thread "EventConsume-ELE_REG_EVENT" org.redisson.client.RedisResponseTimeoutException: Redis server response timeout (3000 ms) occured after 3 retry attempts. Command: (LINDEX), params: [ele.test.message, 0], channel: [id: 0x57c40788, L:/172.17.0.7:59636 - R:192.168.0.13/192.168.0.13:6379]
	at org.redisson.command.CommandAsyncService$8.run(CommandAsyncService.java:935)
	at io.netty.util.HashedWheelTimer$HashedWheelTimeout.expire(HashedWheelTimer.java:682)
	at io.netty.util.HashedWheelTimer$HashedWheelBucket.expireTimeouts(HashedWheelTimer.java:757)
	at io.netty.util.HashedWheelTimer$Worker.run(HashedWheelTimer.java:485)
	at java.lang.Thread.run(Thread.java:745)
```
这个是配置问题 需要在配置Redisson客户端的时候改用下面的配置：

```java
@Configuration
public class RedissonConf {

    @Value("${redis.uri}")
    private String redisUri;

     @Bean
        public RedissonClient redissonClient() {
            Config config = new Config();
            config.setThreads(2);
            config.setTransportMode(TransportMode.NIO);
            config.useSingleServer()
                    .setKeepAlive(true)
                    .setPingConnectionInterval(60)
                    .setAddress(redisUri);
            return Redisson.create(config);
        }


    @Bean
    public EventService eventService(@Autowired List<EventCallback> callbackList){
        EventService service = new EventService();
        service.setEventCallbacks(callbackList);
        service.setEventTypes(EventEnum.listOf());
        return service;
    }
}
```

## 运行一段时间后报错 `ClosedChannelException` 怎么处理？

当我遇到这里问题时，我求助于github上的代码提供者并且得到了解决方案。

> set connectionMinimumIdleSize = connectionPoolSize = 64
>  set subscriptionConnectionPoolSize = subscriptionConnectionMinimumIdleSize = 50

把这两个参数设置成一样的就好了。


## 鸣谢
- 本项目灵感来源于以前我的同事@周红阳。他实现了一套基于mq的event机制
- 感谢redisson这个项目对于redis的强大封装


# ChangeLog
- v1.0.4 处理没有事件时的异常
- v1.0.3 使用锁解决出现少读和幻读问题。
- v1.0.2 解决消费一段时间后链接中断的问题
