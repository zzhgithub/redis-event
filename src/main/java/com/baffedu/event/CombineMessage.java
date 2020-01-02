package com.baffedu.event;

import lombok.Data;

import java.io.Serializable;

@Data
public class CombineMessage implements Serializable {
    private String systemName;
    private String id;
    private String content;
}
