package com.yupi.yupicturebackend.utils;

public class ColorTransformUtils {
    private ColorTransformUtils(){

    }

    public static String getStandardColor(String color){
        if (color.length() == 7){
            color = color.substring(0,4) + "0" + color.substring(4,7);
        }

        return color;
    }
}
