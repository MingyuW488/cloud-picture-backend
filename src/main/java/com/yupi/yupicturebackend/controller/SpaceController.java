package com.yupi.yupicturebackend.controller;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yupi.yupicturebackend.annotation.AuthCheck;
import com.yupi.yupicturebackend.common.BaseResponse;
import com.yupi.yupicturebackend.common.DeleteRequest;
import com.yupi.yupicturebackend.common.ResultUtils;
import com.yupi.yupicturebackend.constant.UserConstant;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.exception.ErrorCode;
import com.yupi.yupicturebackend.exception.ThrowUtils;
import com.yupi.yupicturebackend.manager.auth.SpaceUserAuthManager;
import com.yupi.yupicturebackend.model.dto.space.*;
import com.yupi.yupicturebackend.model.entity.Space;
import com.yupi.yupicturebackend.model.entity.User;
import com.yupi.yupicturebackend.model.enums.SpaceLevelEnum;
import com.yupi.yupicturebackend.model.vo.LoginUserVO;
import com.yupi.yupicturebackend.model.vo.SpaceVO;
import com.yupi.yupicturebackend.service.SpaceService;
import com.yupi.yupicturebackend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RestController
@Slf4j
@RequestMapping("/space")
public class SpaceController {
    @Resource
    private SpaceService spaceService;
    @Resource
    private UserService userService;
    @Resource
    private SpaceUserAuthManager spaceUserAuthManager;


    /**
     * 创建空间
     * @param spaceAddRequest
     * @param request
     * @return
     */
    @PostMapping("/add")
    public BaseResponse<Long> addSpace(@RequestBody SpaceAddRequest spaceAddRequest,HttpServletRequest request){
        ThrowUtils.throwIf(spaceAddRequest == null,ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        long id = spaceService.addSpace(spaceAddRequest, loginUser);
        return ResultUtils.success(id);
    }

    /**
     *  删除空间
     * @param deleteRequest
     * @param request
     * @return
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteSpace(@RequestBody DeleteRequest deleteRequest,HttpServletRequest request){
        if (deleteRequest == null || deleteRequest.getId() <= 0 ){
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }

        User loginUser = userService.getLoginUser(request);
        Long id = deleteRequest.getId();
        //判断是否有空间
        Space oldSpace = spaceService.getById(id);
        ThrowUtils.throwIf(oldSpace == null,ErrorCode.NOT_FOUND_ERROR);
        //本人或管理员才能删除
        spaceService.checkSpaceAuth(oldSpace,loginUser);


        //操作数据库
        boolean res = spaceService.removeById(id);
        ThrowUtils.throwIf(!res,ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 更新空间（仅管理员
     * @param request
     * @return
     */
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @PostMapping("/update")
    public BaseResponse<Boolean> updateSpace(@RequestBody SpaceUpdateRequest spaceUpdateRequest,HttpServletRequest request){
        if (spaceUpdateRequest == null || spaceUpdateRequest.getId() <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }

        //DTO转换为实体类
        Space space = new Space();
        BeanUtils.copyProperties(spaceUpdateRequest,space);

        //数据校验
        spaceService.validSpace(space,false);
        long id = spaceUpdateRequest.getId();
        Space oldSpace = spaceService.getById(id);
        ThrowUtils.throwIf(oldSpace == null,ErrorCode.NOT_FOUND_ERROR);

        //补充审核参数
        spaceService.fillSpaceBySpaceLevel(space);
        //操作数据库
        boolean res = spaceService.updateById(space);
        ThrowUtils.throwIf(!res,ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);

    }


    /**
     * 根据id 获取空间封装类
     * @param id
     * @param request
     * @return
     */
    @GetMapping("/get/vo")
    public  BaseResponse<SpaceVO> getSpaceVOById(long id,HttpServletRequest request){
        ThrowUtils.throwIf(id <= 0,ErrorCode.PARAMS_ERROR);
        //查询数据库
        Space space = spaceService.getById(id);
        ThrowUtils.throwIf(space == null,ErrorCode.NOT_FOUND_ERROR);
        User loginUser = userService.getLoginUser(request);
        List<String> permissionList = spaceUserAuthManager.getPermissionList(space, loginUser);
        SpaceVO spaceVO = spaceService.getSpaceVO(space, request);
        spaceVO.setPermissionList(permissionList);

        //获取封装类
        return ResultUtils.success(spaceVO);
    }

    /**
     * 分页获取空间列表(实体类
     * @param spaceQueryRequest
     * @return Page<Space></>实体类
     */
    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<Space>> listSpaceByPage(@RequestBody SpaceQueryRequest spaceQueryRequest ){
        long current = spaceQueryRequest.getCurrent();
        long size = spaceQueryRequest.getPageSize();
        //查询数据库
        Page<Space> spacePage = spaceService.page(new Page<>(current,size),
                spaceService.getQueryWrapper(spaceQueryRequest));
        return ResultUtils.success(spacePage);

    }

    /**
     * 分页获取空间列表（封装类
     * @param spaceQueryRequest
     * @param request
     * @return 空间封装类j
     */
    @PostMapping("/list/page/vo")
    public BaseResponse<Page<SpaceVO>> listSpaceVOByPage(@RequestBody SpaceQueryRequest spaceQueryRequest, HttpServletRequest request){
        long current = spaceQueryRequest.getCurrent();
        long size = spaceQueryRequest.getPageSize();

        ThrowUtils.throwIf(size > 20,ErrorCode.PARAMS_ERROR);


        //查询数据库
        Page<Space> spacePage = spaceService.page(new Page<>(current,size),
                spaceService.getQueryWrapper(spaceQueryRequest));

        //获取封装类
        return ResultUtils.success(spaceService.getSpaceVOPage(spacePage,request));

    }

    /**
     * 编辑空间（用户使用
     */
    @PostMapping("/edit")
    public BaseResponse<Boolean> editSpace(@RequestBody SpaceEditRequest spaceEditRequest,HttpServletRequest request){
        if (spaceEditRequest == null || spaceEditRequest.getId() <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        //DTO转为实体类
        Space space = new Space();
        BeanUtils.copyProperties(spaceEditRequest,space);

        //设置编辑时间
        space.setEditTime(new Date());

        //数据校验
        spaceService.validSpace(space,false);
        User loginUser = userService.getLoginUser(request);
        //判断是否存在
        long id = spaceEditRequest.getId();
        Space oldSpace = spaceService.getById(id);
        ThrowUtils.throwIf(oldSpace == null,ErrorCode.NOT_FOUND_ERROR);
        //本人或管理可以编辑
        spaceService.checkSpaceAuth(oldSpace,loginUser);

        //补充审核参数
        spaceService.fillSpaceBySpaceLevel(space);
        //操作数据库
        boolean res = spaceService.updateById(space);
        ThrowUtils.throwIf(!res,ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);

    }

    /**
     * 获取所有的空间级别,展示给前端
     * @return
     */
    @GetMapping("/list/level")
    public BaseResponse<List<SpaceLevel>> listSpaceLevel(){
        List<SpaceLevel> spaceLevelList = Arrays.stream(SpaceLevelEnum.values())
                .map(spaceLevelEnum -> new SpaceLevel(spaceLevelEnum.getValue(), spaceLevelEnum.getText(), spaceLevelEnum.getMaxCount(), spaceLevelEnum.getMaxSize())
                ).collect(Collectors.toList());
        return ResultUtils.success(spaceLevelList);
    }


}
