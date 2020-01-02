package com.baffedu.event;

import org.redisson.api.RBlockingQueue;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@ThreadSafe
public class EventService {

    private static final Logger logger = LoggerFactory.getLogger(EventService.class);
    @Autowired
    private RedissonClient redissonClient;
    @GuardedBy("this")
    private Collection<EventCallback> eventCallbacks;
    @GuardedBy("this")
    private Collection<EventType> eventTypes;

    private Executor executor;

    private String systemName;

    public void setSystemName(String systemName) {
        this.systemName = systemName;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    public String getSystemName() {
        String ipTag = "";
        try {
            InetAddress inetAddress = InetAddress.getLocalHost();
            ipTag = "[" + inetAddress.getHostAddress() + "]";
        } catch (Exception e) {
            logger.warn(e.getMessage());
        }
        return systemName + ipTag;
    }

    //    private static final ConcurrentHashMap<String, EventType> eventTypeConcurrentHashMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<EventType, RBlockingQueue<CombineMessage>> eventWaitingForSend = new ConcurrentHashMap<>();

    public void setEventCallbacks(Collection<EventCallback> eventCallbacks) {
        this.eventCallbacks = eventCallbacks;
    }

    public void setEventTypes(Collection<EventType> eventTypes) {
        this.eventTypes = eventTypes;
    }

    @PostConstruct
    public void init() {
        //初始化
        List<EventType> backTypes = eventCallbacks.stream().map(EventCallback::getSupportEventType).collect(Collectors.toList());
        List<EventType> allTypes = new ArrayList<>();
        allTypes.addAll(backTypes);
        if (eventTypes != null) {
            allTypes.addAll(eventTypes);
        }
        allTypes = allTypes.stream().distinct().collect(Collectors.toList());
        for (EventType type : allTypes) {
            eventWaitingForSend.put(type, redissonClient.getBlockingDeque(type.getEventName()));
        }
        for (EventCallback callback : this.eventCallbacks) {
            RLock lock = redissonClient.getLock(callback.getSupportEventType().getEventName() + "@Lock");
            Thread t = new Thread(new EventConsume(callback.getSupportEventType(), eventWaitingForSend.get(callback.getSupportEventType()), callback, getSystemName(), lock, redissonClient));
            t.setName("EventConsume-" + callback.getSupportEventType());
            t.setDaemon(true);
            t.start();
        }
        logger.info("start with the redis message queue!");
    }


    /**
     * send event to queue
     *
     * @param type
     * @param content
     * @param <E>
     */
    public <E> void publishEvent(EventType<E> type, E content) {
        Objects.requireNonNull(type, "parameter type should NOT be null");
        Objects.requireNonNull(content, "parameter msg should NOT be null");
        if (!type.getContentType().equals(content.getClass())) {
            throw new IllegalArgumentException("parameter content must be instance of " + type.getContentType());
        }
        UUID uuid = UUID.randomUUID();
        String mId = uuid.toString();
        RBlockingQueue<CombineMessage> queue = eventWaitingForSend.get(type);
        CombineMessage combineMessage = new CombineMessage();
        combineMessage.setSystemName(getSystemName());
        combineMessage.setId(mId);
        combineMessage.setContent(EventUtil.gson().toJson(content));

        // 判断队列最大长度的问题?

        if (Objects.nonNull(executor)) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        queue.offer(combineMessage, 10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        logger.info("cant send event In execute Msg: {}", e.getMessage());
                    }
                }
            });
        } else {
            try {
                queue.offer(combineMessage, 10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                logger.info("cant send event Msg: {}", e.getMessage());
            }
        }

        logger.info("System {} send event: {} with M-id: {}", getSystemName(), type.getEventName(), mId);
    }
}
