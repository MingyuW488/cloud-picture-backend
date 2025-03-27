package com.yupi.yupicturebackend.mapper;

import com.yupi.yupicturebackend.model.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;


public interface UserMapper extends BaseMapper<User> {

    @Select("select * from cloud_picture.user where userAccount = #{userAccount}")
    User selectByUserAccount(String userAccount);

}




