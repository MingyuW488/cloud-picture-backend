package com.yupi.yupicturebackend.exception;

import lombok.extern.slf4j.Slf4j;

/**
 * 异常处理工具类
 */
@Slf4j
public class ThrowUtils {

    public static void throwIf(boolean condition,RuntimeException runtimeException){
        if(condition){
            throw runtimeException;
        }
    }
    public static void throwIf(boolean condition, ErrorCode errorCode){
        if(condition){
            throw new BusinessException(errorCode);
        }
    }
    /**
     *
     * @param condition
     * @param errorCode
     * @param message
     */
    public static void throwIf(boolean condition,ErrorCode errorCode,String message){

        if (condition){
            log.info(message);
            throw new BusinessException(errorCode,message);
        }
    }
}
