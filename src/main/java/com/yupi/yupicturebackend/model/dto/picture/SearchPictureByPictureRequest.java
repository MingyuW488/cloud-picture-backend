package com.yupi.yupicturebackend.model.dto.picture;

import lombok.Data;

import java.io.Serializable;

/**
 * 以图搜图请求类
 */
@Data
public class SearchPictureByPictureRequest implements Serializable {


    /**
     *  图片ID
     */
    private long pictureId;

    private static final long serialVersionUID = 1L;
}
