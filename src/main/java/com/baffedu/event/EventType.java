package com.baffedu.event;

/**
 * Event type. Implements should override default methods equals and hashCode. since it will be used as Map key.
 * Created by zhouhongyang@zbj.com on 12/26/2017.
 *
 * @param <E> content(event body) class.
 */
public interface EventType<E> {
    /**
     * @return name of the event, should be unique in the universe. e.g. com.zomwork.trade.request.published
     */
    String getEventName();

    /**
     * @return content(event body) class.
     */
    Class<E> getContentType();
}
