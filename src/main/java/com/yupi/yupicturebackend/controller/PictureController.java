package com.yupi.yupicturebackend.controller;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yupi.yupicturebackend.annotation.AuthCheck;
import com.yupi.yupicturebackend.api.imagesearch.SoImageSearchApiFacade;
import com.yupi.yupicturebackend.api.imagesearch.model.SoImageSearchResult;
import com.yupi.yupicturebackend.common.BaseResponse;
import com.yupi.yupicturebackend.common.DeleteRequest;
import com.yupi.yupicturebackend.common.ResultUtils;
import com.yupi.yupicturebackend.constant.UserConstant;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.exception.ErrorCode;
import com.yupi.yupicturebackend.exception.ThrowUtils;
import com.yupi.yupicturebackend.model.dto.picture.*;
import com.yupi.yupicturebackend.model.entity.Picture;
import com.yupi.yupicturebackend.model.entity.Space;
import com.yupi.yupicturebackend.model.entity.User;
import com.yupi.yupicturebackend.model.enums.PictureReviewStatusEnum;
import com.yupi.yupicturebackend.model.vo.PictureTagCategory;
import com.yupi.yupicturebackend.model.vo.PictureVO;
import com.yupi.yupicturebackend.service.PictureService;
import com.yupi.yupicturebackend.service.SpaceService;
import com.yupi.yupicturebackend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.statement.select.Pivot;
import org.apache.catalina.webresources.ExtractingRoot;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import springfox.documentation.spring.web.json.Json;
import sun.print.BackgroundServiceLookup;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@Slf4j
@RequestMapping("/picture")
public class PictureController {
    @Resource
    private PictureService pictureService;
    @Resource
    private UserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private SpaceService spaceService;
    @Resource
    private TransactionTemplate transactionTemplate;

    //caffeine本地缓存配置
    private final Cache<String, String> LOCAL_CACHE =
            Caffeine.newBuilder().initialCapacity(1024)
                    .maximumSize(10000L)
                    // 缓存 5 分钟移除
                    .expireAfterWrite(5L, TimeUnit.MINUTES)
                    .build();


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
     * @param pictureUploadRequest
     * @param request
     * @return
     */
    @PostMapping("/upload/url")
//    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<PictureVO> uploadPictureByUrl(@RequestBody PictureUploadRequest pictureUploadRequest, HttpServletRequest request){
        User loginUser = userService.getLoginUser(request);
        String url = pictureUploadRequest.getFileUrl();
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
        transactionTemplate.execute(status -> {
            //操作数据库
            boolean res = pictureService.removeById(id);
            ThrowUtils.throwIf(!res,ErrorCode.OPERATION_ERROR,"数据库操作失败");
            boolean update = spaceService.lambdaUpdate()
                    .eq(Space::getId,oldPicture.getSpaceId())
                    .setSql("totalSize = totalSize -" + oldPicture.getPicSize())
                    .setSql("totalCount = totalCount - 1")
                    .update();
            ThrowUtils.throwIf(!update,ErrorCode.OPERATION_ERROR,"额度更新失败");
            return true;
        });


        //本人或管理员才能删除
        pictureService.checkPictureAuth(loginUser,oldPicture);
        pictureService.clearPictureFile(oldPicture);

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
        if (picture.getSpaceId() != null){
            User loginUser = userService.getLoginUser(request);
            pictureService.checkPictureAuth(loginUser,picture);
        }
        //获取封装类
        return ResultUtils.success(pictureService.getPictureVO(picture,request));
    }

    /**
     * 分页获取图片列表(实体类
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

        //空间权限校验
        Long spaceId = pictureQueryRequest.getSpaceId();
        if (spaceId == null){
            //公开图库
            //普通用户默认只能看到审核通过的数据
            pictureQueryRequest.setNullSpaceId(true);
            pictureQueryRequest.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
        }else {
            //个人空间
            User loginUser = userService.getLoginUser(request);
            Space space = spaceService.getById(spaceId);
            ThrowUtils.throwIf(space == null,ErrorCode.NOT_FOUND_ERROR,"空间不存在");
            if (!loginUser.getId().equals(space.getUserId())){
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"没有空间权限");
            }
        }
        //查询数据库
        Page<Picture> picturePage = pictureService.page(new Page<>(current,size),
                pictureService.getQueryWrapper(pictureQueryRequest));

        //获取封装类
        return ResultUtils.success(pictureService.getPictureVOPage(picturePage,request));

    }
    /**
     * 分页获取图片列表（封装类 （
     * @param pictureQueryRequest
     * @param request
     * @return 图片封装类j,(redis缓存
     */
    @PostMapping("/list/page/vo/cache")
    public BaseResponse<Page<PictureVO>> listPictureVOByPageWithCache(@RequestBody PictureQueryRequest pictureQueryRequest, HttpServletRequest request){
        long current = pictureQueryRequest.getCurrent();
        long size = pictureQueryRequest.getPageSize();

        ThrowUtils.throwIf(size > 20,ErrorCode.PARAMS_ERROR);
        //普通用户默认只能看到审核通过的数据
        pictureQueryRequest.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());

        //构造缓存key
        String queryCondition = JSONUtil.toJsonStr(pictureQueryRequest);
        String hashKey = DigestUtils.md5DigestAsHex(queryCondition.getBytes());
        String cacheKey = "couldpicture:listPictureVOByPage" + hashKey;
        //1.查询本地缓存
        String cachedValue = LOCAL_CACHE.getIfPresent(cacheKey);
        //如果命中缓存， 返回结果
        if(cachedValue != null){
            Page<PictureVO> cachePage = JSONUtil.toBean(cachedValue,Page.class);
            return ResultUtils.success(cachePage);
        }

        //2.查询redis缓存
        ValueOperations<String, String> valueOperations = stringRedisTemplate.opsForValue();
        cachedValue = valueOperations.get(cacheKey);
        if (cachedValue != null){
            //redis命中,存入本地缓存并返回结果
            LOCAL_CACHE.put(cacheKey,cachedValue);
            Page<PictureVO> cachedPage = JSONUtil.toBean(cachedValue,Page.class);
            return ResultUtils.success(cachedPage);
        }
        //3.redis未命中，则查询数据库并存储到本地缓存和redis中
        Page<Picture> picturePage = pictureService.page(new Page<>(current,size),
                pictureService.getQueryWrapper(pictureQueryRequest));
        Page<PictureVO> pictureVOPage = pictureService.getPictureVOPage(picturePage,request);
        //4.更新缓存
        String cacheValue = JSONUtil.toJsonStr(pictureVOPage);
        //更新本地缓存
        LOCAL_CACHE.put(cacheKey,cacheValue);
        //更新redis缓存
        int cacheExpireTime = 300 + RandomUtil.randomInt(0,300);
        valueOperations.set(cacheKey,cacheValue,cacheExpireTime, TimeUnit.SECONDS );
        return ResultUtils.success(pictureVOPage);

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
        pictureService.checkPictureAuth(loginUser,oldPicture);
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

    /**
     * 管理员批量抓取图片
     * @param pictureUploadByBatchRequest
     * @param request
     * @return
     */
    @PostMapping("/upload/batch")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Integer> uploadPictureByBatch(
            @RequestBody PictureUploadByBatchRequest pictureUploadByBatchRequest,
            HttpServletRequest request
    ) {
        ThrowUtils.throwIf(pictureUploadByBatchRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        int uploadCount = pictureService.uploadPictureByBatch(pictureUploadByBatchRequest, loginUser);
        return ResultUtils.success(uploadCount);
    }

    /**
     * 以图搜图
     */
    @PostMapping("/search/picture")
    public BaseResponse<List<SoImageSearchResult>> searchPictureByPicture(@RequestBody SearchPictureByPictureRequest searchPictureByPictureRequest) {
        ThrowUtils.throwIf(searchPictureByPictureRequest == null, ErrorCode.PARAMS_ERROR);
        Long pictureId = searchPictureByPictureRequest.getPictureId();
        ThrowUtils.throwIf(pictureId == null || pictureId <= 0, ErrorCode.PARAMS_ERROR);
        Picture oldPicture = pictureService.getById(pictureId);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);
        List<SoImageSearchResult> resultList = new ArrayList<>();
        //  start 是控制查询多少页, 每页是 20 条
        int start = 0;
        while (resultList.size() <= 30) {
            List<SoImageSearchResult> tempList = SoImageSearchApiFacade.searchImage(
                    StrUtil.isNotBlank(oldPicture.getThumbnailUrl()) ? oldPicture.getThumbnailUrl() : oldPicture.getUrl(), start);
            if (tempList.isEmpty()){
                break;
            }
            resultList.addAll(tempList);
            start += tempList.size();
        }
        return ResultUtils.success(resultList);
    }

    /**
     * 根据颜色搜索
     */
    @PostMapping("/search/color")
    public BaseResponse<List<PictureVO>> searchPictureByColor(@RequestBody SearchPictureByColorRequest searchPictureByColorRequest,HttpServletRequest request){
        ThrowUtils.throwIf(searchPictureByColorRequest == null,ErrorCode.PARAMS_ERROR);

        String picColor = searchPictureByColorRequest.getPicColor();
        Long spaceId = searchPictureByColorRequest.getSpaceId();
        User loginUser = userService.getLoginUser(request);
        List<PictureVO> pictureVOList = pictureService.searchPictureByColor(spaceId, picColor, loginUser);

        return ResultUtils.success(pictureVOList);


    }

    /**
     * 批量编辑图片 （标签和分类
     * @param pictureEditByBatchRequest
     * @param request
     * @return
     */
    @PostMapping("/edit/batch")
    public BaseResponse<Boolean> editPictureByBatch(@RequestBody PictureEditByBatchRequest pictureEditByBatchRequest,HttpServletRequest request){
        ThrowUtils.throwIf(pictureEditByBatchRequest == null,ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        pictureService.editPictureByBatch(pictureEditByBatchRequest,loginUser);
        return ResultUtils.success(true);
    }
}
