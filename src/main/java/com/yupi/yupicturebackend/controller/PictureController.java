package com.yupi.yupicturebackend.controller;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yupi.yupicturebackend.annotation.AuthCheck;
import com.yupi.yupicturebackend.common.BaseResponse;
import com.yupi.yupicturebackend.common.DeleteRequest;
import com.yupi.yupicturebackend.common.ResultUtils;
import com.yupi.yupicturebackend.constant.UserConstant;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.exception.ErrorCode;
import com.yupi.yupicturebackend.exception.ThrowUtils;
import com.yupi.yupicturebackend.model.dto.picture.*;
import com.yupi.yupicturebackend.model.dto.user.UserLoginRequest;
import com.yupi.yupicturebackend.model.entity.Picture;
import com.yupi.yupicturebackend.model.entity.User;
import com.yupi.yupicturebackend.model.enums.PictureReviewStatusEnum;
import com.yupi.yupicturebackend.model.vo.PictureTagCategory;
import com.yupi.yupicturebackend.model.vo.PictureVO;
import com.yupi.yupicturebackend.service.PictureService;
import com.yupi.yupicturebackend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.webresources.ExtractingRoot;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import sun.print.BackgroundServiceLookup;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

@RestController
@Slf4j
@RequestMapping("/picture")
public class PictureController {
    @Resource
    private PictureService pictureService;
    @Resource
    private UserService userService;

    /**
     * 上传图片
     * @param multipartFile
     * @param pictureUploadRequest
     * @param request
     * @return
     */
    @PostMapping("/upload")
//    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<PictureVO> uploadPicture(@RequestPart("file") MultipartFile multipartFile,
                                                 PictureUploadRequest
                                                 pictureUploadRequest, HttpServletRequest request){
        User loginUser = userService.getLoginUser(request);
        PictureVO pictureVO = pictureService.uploadPicture(multipartFile, pictureUploadRequest, loginUser);
        return ResultUtils.success(pictureVO);
    }

    /**
     *  根据url上传文件，
     * @param multipartFile
     * @param pictureUploadRequest
     * @param request
     * @return
     */
    @PostMapping("/upload/url")
//    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<PictureVO> uploadPictureByUrl(@RequestBody PictureUploadRequest pictureUploadRequest, HttpServletRequest request){
        User loginUser = userService.getLoginUser(request);
        String url = pictureUploadRequest.getUrl();
        PictureVO pictureVO = pictureService.uploadPicture(url, pictureUploadRequest, loginUser);
        return ResultUtils.success(pictureVO);
    }
    /**
     *  删除图片
     * @param deleteRequest
     * @param request
     * @return
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deletePicture(@RequestBody DeleteRequest deleteRequest,HttpServletRequest request){
        if (deleteRequest == null || deleteRequest.getId() <= 0 ){
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }

        User loginUser = userService.getLoginUser(request);
        Long id = deleteRequest.getId();
        //判断是否有图片
        Picture oldPicture = pictureService.getById(id);
        ThrowUtils.throwIf(oldPicture == null,ErrorCode.NOT_FOUND_ERROR);
        //本人或管理员才能删除
        if (!oldPicture.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }

        //操作数据库
        boolean res = pictureService.removeById(id);
        ThrowUtils.throwIf(!res,ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 更新图片（仅管理员
     * @param request
     * @return
     */
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @PostMapping("/update")
    public BaseResponse<Boolean> updatePicture(@RequestBody PictureUpdateRequest pictureUpdateRequest,HttpServletRequest request){
        if (pictureUpdateRequest == null || pictureUpdateRequest.getId() <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }

        //DTO转换为实体类
        Picture picture = new Picture();
        BeanUtils.copyProperties(pictureUpdateRequest,picture);
        //tags  list to String
        picture.setTags(JSONUtil.toJsonStr(pictureUpdateRequest.getTags()));

        //数据校验
        pictureService.validPicture(picture);
        long id = pictureUpdateRequest.getId();
        Picture oldPicture = pictureService.getById(id);
        ThrowUtils.throwIf(oldPicture == null,ErrorCode.NOT_FOUND_ERROR);

        User loginUser = userService.getLoginUser(request);
        //补充审核参数
        pictureService.fillReviewParams(picture,loginUser);
        //操作数据库
        boolean res = pictureService.updateById(picture);
        ThrowUtils.throwIf(!res,ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);

    }


    /**
     * 根据id 获取图片封装类
     * @param id
     * @param request
     * @return
     */
    @GetMapping("/get/vo")
    public  BaseResponse<PictureVO> getPictureVOById(long id,HttpServletRequest request){
        ThrowUtils.throwIf(id <= 0,ErrorCode.PARAMS_ERROR);
        //查询数据库
        Picture picture = pictureService.getById(id);
        ThrowUtils.throwIf(picture == null,ErrorCode.NOT_FOUND_ERROR);

        //获取封装类
        return ResultUtils.success(pictureService.getPictureVO(picture,request));
    }

    /**
     * 分页获取图片列表
     * @param pictureQueryRequest
     * @return Page<Picture></>实体类
     */
    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<Picture>> listPictureByPage(@RequestBody PictureQueryRequest pictureQueryRequest ){
        long current = pictureQueryRequest.getCurrent();
        long size = pictureQueryRequest.getPageSize();
        //查询数据库
        Page<Picture> picturePage = pictureService.page(new Page<>(current,size),
                pictureService.getQueryWrapper(pictureQueryRequest));
        return ResultUtils.success(picturePage);

    }

    /**
     * 分页获取图片列表（封装类
     * @param pictureQueryRequest
     * @param request
     * @return 图片封装类j
     */
    @PostMapping("/list/page/vo")
    public BaseResponse<Page<PictureVO>> listPictureVOByPage(@RequestBody PictureQueryRequest pictureQueryRequest, HttpServletRequest request){
        long current = pictureQueryRequest.getCurrent();
        long size = pictureQueryRequest.getPageSize();

        ThrowUtils.throwIf(size > 20,ErrorCode.PARAMS_ERROR);

        //普通用户默认只能看到审核通过的数据
        pictureQueryRequest.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
        //查询数据库
        Page<Picture> picturePage = pictureService.page(new Page<>(current,size),
                pictureService.getQueryWrapper(pictureQueryRequest));

        //获取封装类
        return ResultUtils.success(pictureService.getPictureVOPage(picturePage,request));

    }

    /**
     * 编辑图片（用户使用
     */
    @PostMapping("/edit")
    public BaseResponse<Boolean> editPicture(@RequestBody PictureEditRequest pictureEditRequest,HttpServletRequest request){
        if (pictureEditRequest == null || pictureEditRequest.getId() <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        //DTO转为实体类
        Picture picture = new Picture();
        BeanUtils.copyProperties(pictureEditRequest,picture);
        picture.setTags(JSONUtil.toJsonStr(pictureEditRequest.getTags()));
        //设置编辑时间
        picture.setEditTime(new Date());

        //数据校验
        pictureService.validPicture(picture);
        User loginUser = userService.getLoginUser(request);
        //判断是否存在
        long id = pictureEditRequest.getId();
        Picture oldPicture = pictureService.getById(id);
        ThrowUtils.throwIf(oldPicture == null,ErrorCode.NOT_FOUND_ERROR);
        //本人或管理可以编辑
        if (!oldPicture.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        //补充审核参数
        pictureService.fillReviewParams(picture,loginUser);
        //操作数据库
        boolean res = pictureService.updateById(picture);
        ThrowUtils.throwIf(!res,ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);

    }

    /**
     * 获取标签类型
     * @return
     */
    @GetMapping("/tag_category")
    public BaseResponse<PictureTagCategory> listPictureTagCategory() {
        PictureTagCategory pictureTagCategory = new PictureTagCategory();
        List<String> tagList = Arrays.asList("热门", "搞笑", "生活", "高清", "艺术", "校园", "背景", "纯真小子", "创意");
        List<String> categoryList = Arrays.asList("模板", "电商", "表情包", "素材", "海报","网络神人");
        pictureTagCategory.setTagList(tagList);
        pictureTagCategory.setCategoryList(categoryList);
        return ResultUtils.success(pictureTagCategory);
    }

    /**
     *  图片审核
     * @param pictureReviewRequest
     * @param request
     * @return
     */
    @PostMapping("/review")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> doPictureReview(@RequestBody PictureReviewRequest pictureReviewRequest,HttpServletRequest request){
        ThrowUtils.throwIf(pictureReviewRequest == null ,ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);

        pictureService.doPictureReview(pictureReviewRequest,loginUser);

        return ResultUtils.success(true);


    }
}
