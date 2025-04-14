package com.yupi.yupicturebackend.service.impl;

import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yupi.yupicturebackend.constant.UserConstant;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.exception.ErrorCode;
import com.yupi.yupicturebackend.exception.ThrowUtils;
import com.yupi.yupicturebackend.manager.auth.StpKit;
import com.yupi.yupicturebackend.model.dto.user.UserLoginRequest;
import com.yupi.yupicturebackend.model.dto.user.UserQueryRequest;
import com.yupi.yupicturebackend.model.dto.user.UserRegisterRequest;
import com.yupi.yupicturebackend.model.entity.User;
import com.yupi.yupicturebackend.model.enums.UserRoleEnum;
import com.yupi.yupicturebackend.model.vo.LoginUserVO;
import com.yupi.yupicturebackend.model.vo.UserVO;
import com.yupi.yupicturebackend.service.UserService;
import com.yupi.yupicturebackend.mapper.UserMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
*/
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService{

    /**
     * 用户注册
     * @param userRegisterRequest
     * @return
     */
    @Override
    public long userRegister(UserRegisterRequest userRegisterRequest) {
        //校验参数
        String userAccount = userRegisterRequest.getUserAccount();
        String userPassword = userRegisterRequest.getUserPassword();
        String checkPassword = userRegisterRequest.getCheckPassword();

        ThrowUtils.throwIf(StrUtil.hasBlank(userAccount,userPassword,checkPassword),ErrorCode.PARAMS_ERROR,"参数为空");

        ThrowUtils.throwIf(userAccount.length() < 4,ErrorCode.PARAMS_ERROR,"账号过短");

        ThrowUtils.throwIf(!userPassword.equals(checkPassword),ErrorCode.PARAMS_ERROR,"两次输入密码不一致");

        ThrowUtils.throwIf(userPassword.length() < 6,ErrorCode.PARAMS_ERROR,"密码过短");

        //检查用户名用户账号是否与数据重复
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount",userAccount);
        Long count = this.baseMapper.selectCount(queryWrapper);

        ThrowUtils.throwIf(count > 0,ErrorCode.PARAMS_ERROR,"账号重复");

        //对密码进行加密
        String encryptPassword = getEncryptPassword(userPassword);

        //插入数据到数据库

        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(encryptPassword);
        user.setUserName("无名");
        //默认为用户
        user.setUserRole(UserRoleEnum.USER.getValue());
        boolean saveResult = this.save(user);

        ThrowUtils.throwIf(!saveResult, ErrorCode.SYSTEM_ERROR,"注册失败，数据库错误");
        return user.getId();
    }

    /**
     * 用户登录
     * @param userLoginRequest
     * @param request
     * @return
     */
    @Override
    public LoginUserVO userLogin(UserLoginRequest userLoginRequest, HttpServletRequest request) {
        String userAccount = userLoginRequest.getUserAccount();
        String userPassword = userLoginRequest.getUserPassword();

        //校验
        ThrowUtils.throwIf(StrUtil.hasBlank(userAccount,userPassword),ErrorCode.PARAMS_ERROR,"账号或密码为空");

        ThrowUtils.throwIf(userAccount.length() < 4,ErrorCode.PARAMS_ERROR,"账号或密码错误");

        ThrowUtils.throwIf(userPassword.length() < 6,ErrorCode.PARAMS_ERROR,"账号或密码错误");

        //对用户传递的密码进行加密
        String encryptPassword = getEncryptPassword(userPassword);

        //查询用户是否存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();

        queryWrapper.eq("userAccount",userAccount);

        queryWrapper.eq("userPassword",encryptPassword);

        User user = this.baseMapper.selectOne(queryWrapper);
        //不存在抛异常
        ThrowUtils.throwIf(user == null,ErrorCode.PARAMS_ERROR,"用户不存在或者密码错误");

        //保存用户的登录态
        request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE,user);
        // 记录用户登录态到 Sa-token，便于空间鉴权时使用，注意保证该用户信息与 SpringSession 中的信息过期时间一致
        StpKit.SPACE.login(user.getId());
        StpKit.SPACE.getSession().set(UserConstant.USER_LOGIN_STATE, user);


        //存在则封装返回
        return this.getLoginUserVO(user);


    }

    /**
     * 获取当前登录用户
     * @param request
     * @return
     */
    @Override
    public User getLoginUser(HttpServletRequest request) {
        //判断是否已经登录
        Object userObj = request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if(currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        Long userid = currentUser.getId();
        currentUser = this.getById(userid);

        //保证数据一致性
        if(currentUser == null){
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);

        }
        return currentUser;
    }

    /**
     * 获取脱敏后的当前用户信息
     * @param user
     * @return
     */
    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if (user == null){
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtils.copyProperties(user,loginUserVO);
        return loginUserVO;
    }

    /**
     * 获取脱敏后的用户信息
     * @param user
     * @return
     */
    @Override
    public UserVO getUserVO(User user) {
        if (user == null){
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user,userVO);
        return userVO;
    }

    /**
     * 获取脱敏后的用户信息列表
     * @param userList
     * @return
     */
    @Override
    public List<UserVO> getUserVOList(List<User> userList) {
        if (userList.size() == 0 || userList == null){
            return new ArrayList<>();
        }

        return userList.stream().map(this::getUserVO).collect(Collectors.toList());
    }

    /**
     * 用户注销
     * @param request
     * @return
     */
    @Override
    public boolean userLogout(HttpServletRequest request) {
        //是否登录
        Object userObj = request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE);
        if(userObj == null ) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"用户未登录");
        }

        //移除登录态
        request.getSession().removeAttribute(UserConstant.USER_LOGIN_STATE);


        return true;
    }

    /**
     * 转为查询对象
     * @param userQueryRequest
     * @return
     */
    @Override
    public QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null){
            throw  new BusinessException(ErrorCode.PARAMS_ERROR,"请求参数为空");

        }

        Long id = userQueryRequest.getId();
        String userName = userQueryRequest.getUserName();
        String userAccount = userQueryRequest.getUserAccount();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        int current = userQueryRequest.getCurrent();
        int pageSize = userQueryRequest.getPageSize();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(ObjUtil.isNotNull(id),"id",id);
        queryWrapper.eq(StrUtil.isNotBlank(userRole),"userRole",userRole);
        queryWrapper.like(StrUtil.isNotBlank(userAccount),"userAccount",userAccount);
        queryWrapper.like(StrUtil.isNotBlank(userName),"userName",userName);
        queryWrapper.like(StrUtil.isNotBlank(userProfile),"userProfile",userProfile);
        queryWrapper.orderBy(StrUtil.isNotEmpty(sortField),sortOrder.equals("ascend"),sortField);
        return queryWrapper;

    }

    /**
     *  获取加密后的密码
     * @param userPassword
     * @return
     */
    @Override
    public String getEncryptPassword(String userPassword){

        //混淆密码
        final String SALT = "wmy";
        return DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());

    }

    /**
     * 是否为管理员
     * @param user
     * @return
     */
    @Override
    public boolean isAdmin(User user) {
        return user != null && UserRoleEnum.ADMIN.getValue().equals(user.getUserRole());
    }

}




