package com.baffedu.event;

import org.redisson.api.RBlockingQueue;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static jodd.util.ThreadUtil.sleep;

@NotThreadSafe
public class EventConsume implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(EventConsume.class);

    private RLock lock;
    private final EventType eventType;
    private RBlockingQueue<CombineMessage> queue;
    private final EventCallback eventCallback;

    /**
     * redissson 原始客户端
     */
    private RedissonClient redissonClient;

    /**
     * FIXME: 理论上 为false是用户控制的失败行为不应该尝试这么多次！
     * 重试次数
     */
    private final int TIMES = 3;
    private String systemName;

    public EventConsume(EventType eventType, RBlockingQueue<CombineMessage> queue, EventCallback eventCallback, String systemName, RLock rLock, RedissonClient redissonClient) {
        this.eventType = eventType;
        this.queue = queue;
        this.eventCallback = eventCallback;
        this.systemName = systemName;
        this.lock = rLock;
        this.redissonClient = redissonClient;
    }

    @Override
    public void run() {
        while (true) {
            Boolean locked = false;
            try {
                if (this.queue.peek() != null) {
                    try {
                        locked = lock.tryLock();
                        CombineMessage message;
                        if (locked) {
                            logger.warn("get Lock success!");
                            message = queue.poll(10, TimeUnit.SECONDS);
                            lock.unlock();
                            if (Objects.isNull(message)) {
                                continue;
                            }
                        } else {
                            logger.warn("get Lock  not success");
                            continue;
                        }
                        // 重试
                        Boolean flag = false;
                        int i = 0;
                        while (!flag && i < TIMES) {
                            logger.info("System {} On consuming Event System {} event {} M-ID:{} try: {}", systemName, message.getSystemName(), eventCallback.getSupportEventType().getEventName(), message.getId(), i);
                            flag = eventCallback.consume(EventUtil.gson().fromJson(message.getContent(), eventType.getContentType()));
                            i++;
                        }
                        if (!flag) {
                            //FIXME 消息处理失败的流程
                            logger.error("System {} consume System {} event {} M-ID:{} Fail!!", systemName, message.getSystemName(), eventCallback.getSupportEventType().getEventName(), message.getId());
                            // todo 失败后怎么处理？ 是否可以进入失败队列
                        } else {
                            logger.info("System {} consume System {} event {} M-ID:{} success", systemName, message.getSystemName(), eventCallback.getSupportEventType().getEventName(), message.getId());
                        }
                    } catch (Exception e) {
                        logger.warn("获取数据时报错");
                        e.printStackTrace();
                        sleep(2 * 1000);
                        if (locked) {
                            unLock();
                        }
                        logger.warn("Lock status :{}", locked);
                    }
                }
            } catch (Exception e) {
                logger.warn("链接异常，2秒后重试");
                logger.warn("Lock status :{}", locked);
                if (locked) {
                    unLock();
                }
                sleep(2 * 1000);
                e.printStackTrace();
            }
        }
    }


    /**
     * 使用future解锁
     */
    public void unLock() {
        this.lock.unlockAsync(60 * 1000L).thenAccept(res -> {
            logger.warn("unLock {} is {}", this.lock.getName(), res);
        }).exceptionally(throwable -> {
            throwable.printStackTrace();
            return null;
        });
    }
}
