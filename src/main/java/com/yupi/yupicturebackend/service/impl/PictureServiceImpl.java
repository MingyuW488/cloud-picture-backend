package com.yupi.yupicturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yupi.yupicturebackend.api.aliyunai.AliYunAiApi;
import com.yupi.yupicturebackend.api.aliyunai.model.CreateOutPaintingTaskRequest;
import com.yupi.yupicturebackend.api.aliyunai.model.CreateOutPaintingTaskResponse;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.exception.ErrorCode;
import com.yupi.yupicturebackend.exception.ThrowUtils;
import com.yupi.yupicturebackend.manager.CosManager;
import com.yupi.yupicturebackend.manager.upload.FilePictureUpload;
import com.yupi.yupicturebackend.manager.upload.PictureUploadTemplate;
import com.yupi.yupicturebackend.manager.upload.UrlPictureUpload;
import com.yupi.yupicturebackend.model.dto.file.UploadPictureResult;
import com.yupi.yupicturebackend.model.dto.picture.*;
import com.yupi.yupicturebackend.model.entity.Picture;
import com.yupi.yupicturebackend.model.entity.Space;
import com.yupi.yupicturebackend.model.entity.User;
import com.yupi.yupicturebackend.model.enums.PictureReviewStatusEnum;
import com.yupi.yupicturebackend.model.vo.PictureVO;
import com.yupi.yupicturebackend.model.vo.UserVO;
import com.yupi.yupicturebackend.service.PictureService;
import com.yupi.yupicturebackend.mapper.PictureMapper;
import com.yupi.yupicturebackend.service.SpaceService;
import com.yupi.yupicturebackend.service.UserService;
import com.yupi.yupicturebackend.utils.ColorSimilarUtils;
import com.yupi.yupicturebackend.utils.ColorTransformUtils;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;


@Slf4j
@Service
public class PictureServiceImpl extends ServiceImpl<PictureMapper, Picture> implements PictureService{
    @Resource
    private UserService userService;
    @Resource
    private FilePictureUpload filePictureUpload;
    @Resource
    private UrlPictureUpload urlPictureUpload;
    @Resource
    private CosManager cosManager;
    @Resource
    private SpaceService spaceService;
    @Resource
    private TransactionTemplate transactionTemplate;
    @Resource
    private AliYunAiApi aliYunAiApi;

    /**
     *  图片上传
     * @param pictureUploadRequest
     * @param loginUser
     * @return
     */
    @Override
    public PictureVO uploadPicture(Object inputSource, PictureUploadRequest pictureUploadRequest, User loginUser) {
        //校验参数
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        //判断是新增还是更新
        Long pictureId = null;
        Long spaceId = pictureUploadRequest.getSpaceId();
        //判断空间是否存在
        if (spaceId != null){
            Space space = spaceService.getById(spaceId);
            ThrowUtils.throwIf(space == null,ErrorCode.NOT_FOUND_ERROR,"空间不存在");
            //校验是否有空间的权限，仅空间本人才能上传
//            if (!loginUser.getId().equals(space.getUserId())){
//                throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"没有空间权限");
//            }
            //校验额度
            if (space.getTotalCount() > space.getMaxCount()){
                throw new BusinessException(ErrorCode.OPERATION_ERROR,"空间条数不足");
            }
            //校验空间
            if (space.getTotalSize() > space.getMaxSize()){
                throw new BusinessException(ErrorCode.OPERATION_ERROR,"空间大小不足");
            }
        }
        //获取图片ID,有则为更新，否则上传
        if (pictureUploadRequest != null){
            pictureId = pictureUploadRequest.getId();
        }
        //如果有图片id则更新。
        if(pictureId != null){
            Picture oldPicture = this.getById(pictureId);
            ThrowUtils.throwIf(oldPicture ==  null,ErrorCode.NOT_FOUND_ERROR,"图片不存在");
            //仅本人和管理员可编辑
//            if (!oldPicture.getUserId().equals(loginUser.getId()) && !loginUser.getUserRole().equals(UserConstant.ADMIN_ROLE)){
//                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
//            }
            //校验空间是否一致
            //无spaceId ，用原有图片的spaceId
            if (spaceId == null){
                if (oldPicture.getSpaceId() != null){
                    spaceId = oldPicture.getSpaceId();
                }
            }else {
                if(ObjUtil.notEqual(spaceId,oldPicture.getSpaceId())){
                    throw new BusinessException(ErrorCode.PARAMS_ERROR,"空间id不一致");
                }
            }
        }
        //上传图片,得到图片信息
        //按用户id 划分目录 =>按照空间划分目录
        String uploadPathPrefix;
        //是否为公共图库
        if (spaceId == null) {
            uploadPathPrefix = String.format("public/%s",loginUser.getId());
        }else{
            uploadPathPrefix = String.format("space/%s",spaceId);
        }
        //根据inputSource类型 ，调用对应的上传方式
        PictureUploadTemplate pictureUploadTemplate = filePictureUpload;
        //如果是String类型，则是url上传
        if(inputSource instanceof String){
            pictureUploadTemplate = urlPictureUpload;
        }
        UploadPictureResult uploadPictureResult = pictureUploadTemplate.uploadPicture(inputSource, uploadPathPrefix);
        //构造入库的图片信息
        Picture picture = new Picture();
        BeanUtils.copyProperties(uploadPictureResult,picture);
        picture.setPicColor(ColorTransformUtils.getStandardColor(picture.getPicColor()));
        picture.setSpaceId(spaceId);

        String picName = uploadPictureResult.getPicName();
        //批量抓取图片入库时，会传入pictureUploadRequest.picName 正常上传则为空，判断是否需要手动去更改图片名称,默认为图片原始名称
        if (StrUtil.isNotBlank(pictureUploadRequest.getPicName()) && uploadPictureResult!=null){
            picName = pictureUploadRequest.getPicName();
        }
        picture.setName(picName);
        picture.setUserId(loginUser.getId());

        //补充审核参数
        this.fillReviewParams(picture,loginUser);

        //操作数据库
        //pictureId 为空是新增，否则是更新
        if(pictureId != null){
            picture.setId(pictureId);
            picture.setEditTime(new Date());
        }
        //开启事务
        Long finalSpaceId = spaceId;
        transactionTemplate.execute(status -> {
            //有则修改，无则插入
            boolean result = this.saveOrUpdate(picture);
            ThrowUtils.throwIf(!result,ErrorCode.OPERATION_ERROR,"数据库操作失败");
            if (finalSpaceId != null){
                boolean update = spaceService.lambdaUpdate()
                        .eq(Space::getId, finalSpaceId)
                        .setSql("totalSize = totalSize + " + picture.getPicSize())
                        .setSql("totalCount = totalCount + 1").update();
                ThrowUtils.throwIf(!update,ErrorCode.OPERATION_ERROR,"额度更新失败");
            }
            return true;
        });


        return PictureVO.objToVo(picture);
    }
    /**
     * 获取单个图片封装，主要封装脱敏后的用户对象
     */
    @Override
    public PictureVO getPictureVO(Picture picture, HttpServletRequest request) {
        // 对象转封装类
        PictureVO pictureVO = PictureVO.objToVo(picture);
        // 关联查询用户信息
        Long userId = picture.getUserId();
        if (userId != null && userId > 0) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            pictureVO.setUser(userVO);
        }
        return pictureVO;
    }

    /**
     *  分页获取图片封装类，
     */
    @Override
    public Page<PictureVO> getPictureVOPage(Page<Picture> picturePage, HttpServletRequest request){
        List<Picture> pictureList = picturePage.getRecords();
        Page<PictureVO> pictureVOPage = new Page<>(picturePage.getCurrent(),picturePage.getSize(),picturePage.getTotal());
        //为空返回
        if(CollUtil.isEmpty(pictureList)){
            return pictureVOPage;
        }
        //对象列表=>封装对象列表
        List<PictureVO> pictureVOList = pictureList.stream().map(PictureVO::objToVo).collect(Collectors.toList());

        //去重查询所有用户信息
        Set<Long> userIdSet = pictureList.stream().map(Picture::getUserId).collect(Collectors.toSet());
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream().collect(Collectors.groupingBy(User::getId));
        //为图片VO类赋值
        pictureVOList.forEach(pictureVO -> {
            Long userId = pictureVO.getUserId();
            User user = null;
            if(userIdUserListMap.containsKey(userId)){
                user = userIdUserListMap.get(userId).get(0);

            }
            pictureVO.setUser(userService.getUserVO(user));
        });
        pictureVOPage.setRecords(pictureVOList);
        return pictureVOPage;



    }
    /**
     *  分页查询条件
     * @param pictureQueryRequest
     * @return
     */
    @Override
    public QueryWrapper<Picture> getQueryWrapper(PictureQueryRequest pictureQueryRequest) {
        QueryWrapper<Picture> queryWrapper = new QueryWrapper<>();
        if (pictureQueryRequest == null) {
            return queryWrapper;
        }
        // 从对象中取值
        Long id = pictureQueryRequest.getId();
        String name = pictureQueryRequest.getName();
        String introduction = pictureQueryRequest.getIntroduction();
        String category = pictureQueryRequest.getCategory();
        List<String> tags = pictureQueryRequest.getTags();
        Long picSize = pictureQueryRequest.getPicSize();
        Integer picWidth = pictureQueryRequest.getPicWidth();
        Integer picHeight = pictureQueryRequest.getPicHeight();
        Double picScale = pictureQueryRequest.getPicScale();
        String picFormat = pictureQueryRequest.getPicFormat();
        String searchText = pictureQueryRequest.getSearchText();
        Long userId = pictureQueryRequest.getUserId();
        String sortField = pictureQueryRequest.getSortField();
        String sortOrder = pictureQueryRequest.getSortOrder();
        Integer reviewStatus = pictureQueryRequest.getReviewStatus();
        Long reviewerId = pictureQueryRequest.getReviewerId();
        String reviewMessage = pictureQueryRequest.getReviewMessage();
        Long spaceId = pictureQueryRequest.getSpaceId();
        boolean nullSpaceId = pictureQueryRequest.isNullSpaceId();
        Date startEditTime = pictureQueryRequest.getStartEditTime();
        Date endEditTime = pictureQueryRequest.getEndEditTime();

        // 从多字段中搜索
        if (StrUtil.isNotBlank(searchText)) {
            // 需要拼接查询条件
            queryWrapper.and(qw -> qw.like("name", searchText)
                    .or()
                    .like("introduction", searchText)
            );
        }
        queryWrapper.eq(ObjUtil.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId), "userId", userId);
        queryWrapper.like(StrUtil.isNotBlank(name), "name", name);
        queryWrapper.like(StrUtil.isNotBlank(introduction), "introduction", introduction);
        queryWrapper.like(StrUtil.isNotBlank(picFormat), "picFormat", picFormat);
        queryWrapper.eq(StrUtil.isNotBlank(category), "category", category);
        queryWrapper.eq(ObjUtil.isNotEmpty(picWidth), "picWidth", picWidth);
        queryWrapper.eq(ObjUtil.isNotEmpty(picHeight), "picHeight", picHeight);
        queryWrapper.eq(ObjUtil.isNotEmpty(picSize), "picSize", picSize);
        queryWrapper.eq(ObjUtil.isNotEmpty(picScale), "picScale", picScale);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewerId), "reviewerId", reviewerId);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewStatus), "reviewStatus", reviewStatus);
        queryWrapper.like(StrUtil.isNotBlank(reviewMessage), "reviewMessage", reviewMessage);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceId), "spaceId", spaceId);
        queryWrapper.isNull(nullSpaceId,"spaceId");
        queryWrapper.ge(ObjUtil.isNotEmpty(startEditTime),"editTime",startEditTime);
        queryWrapper.le(ObjUtil.isNotEmpty(endEditTime),"editTime",endEditTime);


        // JSON 数组查询
        if (CollUtil.isNotEmpty(tags)) {
            for (String tag : tags) {
                queryWrapper.like("tags", "\"" + tag + "\"");
            }
        }
        // 排序
        queryWrapper.orderBy(StrUtil.isNotEmpty(sortField), sortOrder.equals("ascend"), sortField);
        return queryWrapper;
    }

    /**
     * 更新和修改图片时候的校验逻辑
     * @param picture
     */
    @Override
    public void validPicture(Picture picture) {
        ThrowUtils.throwIf(picture == null, ErrorCode.PARAMS_ERROR);
        // 从对象中取值
        Long id = picture.getId();
        String url = picture.getUrl();
        String introduction = picture.getIntroduction();
        // 修改数据时，id 不能为空，有参数则校验
        ThrowUtils.throwIf(ObjUtil.isNull(id), ErrorCode.PARAMS_ERROR, "id 不能为空");
        if (StrUtil.isNotBlank(url)) {
            ThrowUtils.throwIf(url.length() > 1024, ErrorCode.PARAMS_ERROR, "url 过长");
        }
        if (StrUtil.isNotBlank(introduction)) {
            ThrowUtils.throwIf(introduction.length() > 800, ErrorCode.PARAMS_ERROR, "简介过长");
        }
    }

    /**
     * 图片审核
     * @param pictureReviewRequest
     * @param loginUser
     */
    @Override
    public void doPictureReview(PictureReviewRequest pictureReviewRequest, User loginUser) {
        //校验参数
        ThrowUtils.throwIf(pictureReviewRequest == null,ErrorCode.PARAMS_ERROR);
        Long id = pictureReviewRequest.getId();
        Integer reviewStatus = pictureReviewRequest.getReviewStatus();
        PictureReviewStatusEnum reviewStatusEnum = PictureReviewStatusEnum.getEnumByValue(reviewStatus);
        String reviewMessage = pictureReviewRequest.getReviewMessage();
        if (id == null || reviewStatusEnum==null || PictureReviewStatusEnum.REVIEWING.equals(reviewStatusEnum)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        //图片是否存在
        Picture oldPicture = this.getById(id);
        ThrowUtils.throwIf(oldPicture == null,ErrorCode.NOT_FOUND_ERROR);
        //审核状态是否存在
        if (oldPicture.getReviewStatus().equals(reviewStatus)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        //操作数据库
        Picture updatePicture = new Picture();
        BeanUtils.copyProperties(oldPicture,updatePicture);
        updatePicture.setReviewerId(loginUser.getId());
        updatePicture.setReviewTime(new Date());
        updatePicture.setReviewMessage(reviewMessage);
        updatePicture.setReviewStatus(reviewStatus);

        boolean b = this.updateById(updatePicture);
        ThrowUtils.throwIf(!b,ErrorCode.OPERATION_ERROR);

    }

    /**
     * 填充审核信息
     * @param picture
     * @param loginUser
     */
    @Override
    public void fillReviewParams(Picture picture, User loginUser){
        //管理员
        if (userService.isAdmin(loginUser)){
            picture.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
            picture.setReviewerId(loginUser.getId());
            picture.setReviewMessage("管理员上传");
            picture.setReviewTime(new Date());
        }else {
            //非管理员
            picture.setReviewStatus(PictureReviewStatusEnum.REVIEWING.getValue());
        }
    }


    /**
     *  批量抓取图片
     * @param pictureUploadByBatchRequest
     * @param loginUser
     * @return
     */
    @Override
    public Integer uploadPictureByBatch(PictureUploadByBatchRequest pictureUploadByBatchRequest, User loginUser) {

        String searchText = pictureUploadByBatchRequest.getSearchText();
        Integer count = pictureUploadByBatchRequest.getCount();
        ThrowUtils.throwIf(count > 30,ErrorCode.OPERATION_ERROR,"最多抓30条");
        //构造抓图地址
        String fetchUrl = String.format("https://cn.bing.com/images/async?q=%s&mmasync=1",searchText);

        //接收返回的HTML
        Document document;

     //抓取页面
        try {
            document = Jsoup.connect(fetchUrl).get();
        } catch (IOException e) {
            log.error("获取要抓取的页面失败");
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"获取页面失败");
        }
        Element div = document.getElementsByClass("dgControl").first();
        if (ObjUtil.isNull(div)){
            log.error("获取元素失败");
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"获取元素失败");
        }
        //获取图片元素

//        Elements imageElementList = div.select("img.mimg");
        Elements imageElementList = div.select(".iusc");

        // 上传文件计数
        int uploadCount = 0;

        //设置名称前缀
        String namePrefix = pictureUploadByBatchRequest.getNamePrefix(); pictureUploadByBatchRequest.getNamePrefix(); pictureUploadByBatchRequest.getNamePrefix(); pictureUploadByBatchRequest.getNamePrefix();
        if (StrUtil.isBlank(namePrefix)){
            namePrefix = searchText;
        }
        //解析图片链接
        for (Element imgElement: imageElementList){

            String dataM = imgElement.attr("m");
//            String fileUrl = imgElement.attr("src");

            JSONObject jsonObject = JSONUtil.parseObj(dataM);
            String fileUrl = jsonObject.getStr("murl");
            if (StrUtil.isBlank(fileUrl)){
                log.error("当前图片链接为空，跳过");
                continue;
            }
            //处理图片地址，去除？后的条件
            int markIndex = fileUrl.indexOf("?");
            if (markIndex > -1){
                fileUrl = fileUrl.substring(0,markIndex);
            }
            //上传图片到COS对象存储
            PictureUploadRequest pictureUploadRequest = new PictureUploadRequest();
            try {
                //设置图片名称
                pictureUploadRequest.setPicName(namePrefix + (uploadCount + 1));
                PictureVO pictureVO = uploadPicture(fileUrl, pictureUploadRequest, loginUser);
                log.info("图片上传成功id = {}",pictureVO.getId());
                uploadCount++;
            }catch (Exception e){
                log.error("图片上传失败");
                continue;
            }
            //如果抓取已经到达条数则退出
            if(uploadCount >= count){
                break;
            }
        }
        return uploadCount;
    }

    /**
     *  清理图片，删除对象存储文件
     * @param oldPicture
     */
    @Async
    @Override
    public void clearPictureFile(Picture oldPicture) {
        //判断图片是否被多条记录使用
        String oldPictureUrl = oldPicture.getUrl();
        long count = this.lambdaQuery().eq(Picture::getUrl, oldPictureUrl).count();

        if(count >1 ){
            return;
        }
        //删除压缩图
        cosManager.deleteObject(oldPictureUrl);
        String thumbnailUrl = oldPicture.getThumbnailUrl();
        if (StrUtil.isNotBlank(thumbnailUrl)){
            cosManager.deleteObject(thumbnailUrl);
        }
    }

    /**
     * 校验空间权限
     * @param loginUser
     * @param picture
     */
    @Override
    public void checkPictureAuth(User loginUser, Picture picture) {
        Long loginUserId = loginUser.getId();
        Long spaceId = picture.getSpaceId();
        if (spaceId == null){
            //公共空间，本人和管理员可以操作
            if (!picture.getUserId().equals(loginUserId) && !userService.isAdmin(loginUser)){
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
        }
        else {
            //个人空间,空间拥有者能操作
            if (!picture.getUserId().equals(loginUserId)){
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
        }

    }

    /**
     * 根据颜色搜索图片
     * @param spaceId
     * @param picColor
     * @param loginUser
     * @return
     */
    @Override
    public List<PictureVO> searchPictureByColor(Long spaceId, String picColor, User loginUser) {
        //校验参数
        ThrowUtils.throwIf(spaceId == null || StrUtil.isBlank(picColor) ,ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null ,ErrorCode.NO_AUTH_ERROR);
        //校验空间权限
        Space space = spaceService.getById(spaceId);
        ThrowUtils.throwIf(space == null,ErrorCode.NOT_FOUND_ERROR,"空间不存在");
        if (!space.getUserId().equals(loginUser.getId())){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"没有空间访问权限");
        }
        //查询空间所有图片（必须有主色调
        List<Picture> pictureList = lambdaQuery().eq(Picture::getSpaceId, spaceId)
                .isNotNull(Picture::getPicColor)
                .list();
        //没有图片直接返回空列表
        if (CollUtil.isEmpty(pictureList)){
            return new ArrayList<>();
        }
        //将十六进制颜色转换为主色调
        Color targetColor = Color.decode(picColor);

        //计算相似度并排序
        List<Picture> sortedPictureList = pictureList.stream()
                .sorted(Comparator.comparingDouble(picture -> {
                    //计算相似度
                    String hexColor = picture.getPicColor();
                    //没有主色调排序到最后
                    if(StrUtil.isBlank(hexColor)){
                        return Double.MAX_VALUE;
                    }
                    Color pictureColor = Color.decode(hexColor);
                    return -ColorSimilarUtils.calculateSimilarity(targetColor, pictureColor);
                }))
                .limit(15)
                .collect(Collectors.toList());

        //返回结果
        return sortedPictureList.stream().map(PictureVO::objToVo).collect(Collectors.toList());
    }

    /**
     * 批量编辑图片信息
     * @param pictureEditByBatchRequest
     * @param loginUser
     */
    @Override
    public void editPictureByBatch(PictureEditByBatchRequest pictureEditByBatchRequest, User loginUser) {
        //1.校验参数
        List<Long> pictureIdList = pictureEditByBatchRequest.getPictureIdList();
        Long spaceId = pictureEditByBatchRequest.getSpaceId();
        String category = pictureEditByBatchRequest.getCategory();
        List<String> tags = pictureEditByBatchRequest.getTags();
        ThrowUtils.throwIf(CollUtil.isEmpty(pictureIdList),ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null,ErrorCode.NO_AUTH_ERROR);
        //管理员也可以编辑
        if(!userService.isAdmin(loginUser) && spaceId == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        //2.校验空间权限
        Space space = spaceService.getById(spaceId);
        ThrowUtils.throwIf(space == null,ErrorCode.NOT_FOUND_ERROR,"空间不存在");
        if (!userService.isAdmin(loginUser) && !space.getUserId().equals(loginUser.getId())){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"没有权限");
        }
        //3.查询指定图片是否存在 (仅查询需要字段
        List<Picture> pictureList = this.lambdaQuery()
                .select(Picture::getId,Picture::getSpaceId)
                .eq(Picture::getSpaceId,spaceId)
                .in(Picture::getId,pictureIdList)
                .list();
        if (pictureList.isEmpty()){
            return;
        }
        //4. 批量更新分类和标签
        pictureList.forEach(picture -> {
            if (StrUtil.isNotBlank(category)){
                picture.setCategory(category);
            }
            if (CollUtil.isNotEmpty(tags)){
                picture.setTags(JSONUtil.toJsonStr(tags));
            }
        });

        //批量重命名
        String nameRule = pictureEditByBatchRequest.getNameRule();
        fillPictureWithNameRule(pictureList,nameRule);

        //5.操作数据库
        boolean result = this.updateBatchById(pictureList);
        ThrowUtils.throwIf(!result,ErrorCode.OPERATION_ERROR,"批量编辑失败");
    }

    /**
     * AI扩图
     * @param createPictureOutPaintingTaskRequest
     * @param loginUser
     */
    @Override
    public CreateOutPaintingTaskResponse createPictureOutPaintingTask(CreatePictureOutPaintingTaskRequest createPictureOutPaintingTaskRequest, User loginUser) {
        //获取图片
        Long pictureId = createPictureOutPaintingTaskRequest.getPictureId();
        Picture picture = this.getById(pictureId);
        ThrowUtils.throwIf(picture == null,ErrorCode.NOT_FOUND_ERROR,"图片不存在");
        //权限校验
        //改为注解校验

        //创建扩图任务
        CreateOutPaintingTaskRequest createOutPaintingTaskRequest = new CreateOutPaintingTaskRequest();
        CreateOutPaintingTaskRequest.Input input = new CreateOutPaintingTaskRequest.Input();
        input.setImageUrl(picture.getOriginUrl());
        createOutPaintingTaskRequest.setInput(input);
        createOutPaintingTaskRequest.setParameters(createPictureOutPaintingTaskRequest.getParameters());
        //创建任务
        return aliYunAiApi.createOutPaintingTask(createOutPaintingTaskRequest);
    }

    /**
     * 根据命名规则填充图片名称
     * @param pictureList
     * @param nameRule
     */
    private void fillPictureWithNameRule(List<Picture> pictureList, String nameRule) {
        if (StrUtil.isBlank(nameRule) || CollUtil.isEmpty(pictureList)){
            return;
        }

        try{
            long count = 1;
            for (Picture picture: pictureList){
                String pictureName = nameRule.replaceAll("\\{序号}",String.valueOf(count++));
                picture.setName(pictureName);

            }
        }catch (Exception e){
            log.error("名称解析错误");
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"名称解析错误");
        }
    }
}




