package com.cangshu.mvp.service;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(Long id) {
        super("资源不存在：" + id);
    }
}
