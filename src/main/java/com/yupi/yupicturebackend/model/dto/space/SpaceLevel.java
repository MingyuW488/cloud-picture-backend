package com.yupi.yupicturebackend.model.dto.space;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.boot.convert.DataSizeUnit;

@AllArgsConstructor
@Data
public class SpaceLevel {

    /**
     * 值
     */
    private int value;
    /**
     * 中文
     */
    private String text;
    /**
     * 最大条数
     */
    private long maxCount;
    /**
     * 最大容量
     */
    private long maxSize;
}
