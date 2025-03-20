package com.yupi.yupicturebackend.exception;

/**
 * 异常处理工具类
 */
public class ThrowUtils {

    public static void throwIf(boolean condition,RuntimeException runtimeException){
        if(condition){
            throw runtimeException;
        }
    }

    /**
     *
     * @param condition
     * @param errorCode
     * @param message
     */
    public static void throwIf(boolean condition,ErrorCode errorCode,String message){
        throwIf(condition,new BusinessException(errorCode,message));
    }
}
