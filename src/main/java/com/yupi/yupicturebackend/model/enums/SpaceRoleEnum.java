package com.yupi.yupicturebackend.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;


/**
 * 团队空间角色枚举
 */

@Getter
public enum SpaceRoleEnum {
    USER("浏览者","viewer"),
    EDITOR("编辑者","editor"),
    ADMIN("管理员","admin");


    private final String text;

    private final String value;

    SpaceRoleEnum(String text, String value){
        this.text = text;
        this.value = value;
    }

    /**
     * 根据值获取枚举
     * @param value
     * @return
     */
    public static SpaceRoleEnum getEnumByValue(String value){
        if(ObjUtil.isEmpty(value)){
            return null;
        }
        for (SpaceRoleEnum userRoleEnum : SpaceRoleEnum.values()){
            if(userRoleEnum.value.equals(value)){
                return userRoleEnum;
            }

        }
        return null;
    }
}
