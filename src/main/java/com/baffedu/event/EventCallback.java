package com.baffedu.event;

/**
 * Created by zhouhongyang@zbj.com on 12/26/2017.
 * Consume event. implements should be thread safe!
 */
public interface EventCallback<E>{

    /**
     * @return event type which the Callback consumes.
     */
    EventType<E> getSupportEventType();

    /**
     * throw exception or return false will be treated as consumer fail.
     *
     * @param message
     * @return true if consumer successfully.
     */
    boolean consume(E message) throws Exception;
}
