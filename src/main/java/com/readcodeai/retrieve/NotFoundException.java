package com.readcodeai.retrieve;

/** 查不到就明确报错 —— 静默返回空集合会让「没有」和「没查到」混为一谈。 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
