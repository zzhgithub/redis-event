package com.baffedu.event;

import com.google.gson.Gson;

import javax.annotation.concurrent.Immutable;


/**
 * Created by zhouhongyang@zbj.com on 1/22/2018.
 */
@Immutable
public class EventUtil {

    private static final Gson gson = new Gson();

    public static Gson gson() {
        return gson;
    }
}
