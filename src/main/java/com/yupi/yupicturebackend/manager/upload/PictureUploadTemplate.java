package com.yupi.yupicturebackend.manager.upload;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.CIObject;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import com.qcloud.cos.model.ciModel.persistence.ProcessResults;
import com.yupi.yupicturebackend.config.CosClientConfig;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.exception.ErrorCode;
import com.yupi.yupicturebackend.manager.CosManager;
import com.yupi.yupicturebackend.model.dto.file.UploadPictureResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 *  图片上传模板
 */
@Slf4j
@Component
public abstract class PictureUploadTemplate {
    @Resource
    private CosManager cosManager;
    @Resource
    private CosClientConfig cosClientConfig;
    //后缀常量
    private static final Set<String> IMAGE_SUFFIXES = new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "bmp"));

    /**
     * 上传图片
     */
    public UploadPictureResult uploadPicture(Object inputSource, String uploadPathPrefix) {
        //校验图片
        validPicture(inputSource);
        //图片上传地址
        String uuid = RandomUtil.randomString(16);
        String originalFilename = getOriginalFilename(inputSource);
        //拼接文件路径
        String uploadFileName = String.format("%s_%s.%s", DateUtil.formatDate(new Date())
                , uuid, FileUtil.getSuffix(originalFilename));
        String uploadPath = String.format("/%s/%s", uploadPathPrefix, uploadFileName);

        uploadPath = ensureUploadPathHasSuffix(uploadPath, originalFilename); // ← 添加后缀保护
        //解析结果并返回
        File file = null;
        try {
            //上传文件
            file = File.createTempFile(uploadPath, null);
            //处理文件来源
            processFile(inputSource,file);
            //上传COS对象存储
            PutObjectResult putObjectResult = cosManager.putPictureObject(uploadPath, file);
            //获取图片信息对象
            ImageInfo imageInfo = putObjectResult.getCiUploadResult().getOriginalInfo().getImageInfo();
            //获取到图片处理结果
            ProcessResults processResults = putObjectResult.getCiUploadResult().getProcessResults();
            List<CIObject> objectList = processResults.getObjectList();
            if (CollUtil.isNotEmpty(objectList)){
                //获取压缩后的webp文件信息
                CIObject compressedCiObject = objectList.get(0);
                //若没有缩略图则都是用webp文件
                CIObject thumbnailCiObject = compressedCiObject;
                //缩略图
                if (objectList.size() > 1){
                    thumbnailCiObject = objectList.get(1);
                }
                //封装压缩图片的返回结果
                return buildResult(originalFilename,compressedCiObject,thumbnailCiObject,imageInfo,uploadPath);
            }

            return buildResult(imageInfo,uploadPath,originalFilename,file);
        } catch (IOException e) {
            log.error("图片上传到存储对象失败" , e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"上传失败");
        } finally {
            //清理临时文件
            deleteTemplateFIle(file);
        }
    }

    /**
     *
     * 封装返回结果
     * @param originalFilename 原始文件名
     * @param compressedCiObject 压缩后的对象
     * @return
     */
    private UploadPictureResult buildResult(String originalFilename, CIObject compressedCiObject,CIObject thumbnailCiObject,ImageInfo imageInfo ,String uploadPath) {

        // 封装返回结果
        UploadPictureResult uploadPictureResult = new UploadPictureResult();
        //压缩后的原图地址
        uploadPictureResult.setUrl(cosClientConfig.getHost() + "/" + compressedCiObject.getKey());
        uploadPictureResult.setPicName(FileUtil.mainName(originalFilename));
        uploadPictureResult.setPicSize(compressedCiObject.getSize().longValue());
        uploadPictureResult.setPicWidth(compressedCiObject.getWidth());
        uploadPictureResult.setPicHeight(compressedCiObject.getHeight());
        uploadPictureResult.setPicScale(NumberUtil.round(compressedCiObject.getWidth() * 1.0 / compressedCiObject.getHeight(),2).doubleValue());
        uploadPictureResult.setPicFormat(compressedCiObject.getFormat());
        uploadPictureResult.setPicColor(imageInfo.getAve());

        //缩略图地址
        uploadPictureResult.setThumbnailUrl(cosClientConfig.getHost() + "/" + thumbnailCiObject.getKey());

        //封装原图地址
        uploadPictureResult.setOriginUrl(cosClientConfig.getHost() + "/" + uploadPath);

        return uploadPictureResult;
    }




    protected abstract void processFile(Object inputSource, File file) throws IOException;


    protected abstract String getOriginalFilename(Object inputSource);

    protected abstract void validPicture(Object inputSource);

    /**
     * 封装返回结果
     * @param imageInfo
     * @param uploadPath
     * @param originFileName
     * @param file
     * @return
     */
    private UploadPictureResult buildResult(ImageInfo imageInfo,String uploadPath,String originFileName,File file){

        // 封装返回结果
        UploadPictureResult uploadPictureResult = new UploadPictureResult();
        uploadPictureResult.setUrl(cosClientConfig.getHost() + "/" + uploadPath);
        uploadPictureResult.setPicName(FileUtil.mainName(originFileName));
        uploadPictureResult.setPicSize(FileUtil.size(file));
        uploadPictureResult.setPicWidth(imageInfo.getWidth());
        uploadPictureResult.setPicHeight(imageInfo.getHeight());
        uploadPictureResult.setPicScale(NumberUtil.round(imageInfo.getWidth() * 1.0 / imageInfo.getHeight(),2).doubleValue());
        uploadPictureResult.setPicFormat(imageInfo.getFormat());
        uploadPictureResult.setPicColor(imageInfo.getAve());

        return uploadPictureResult;
    }
    /**
     * 删除临时文件
     */
    private static void deleteTemplateFIle (File file){

        if (file != null) {
            //删除临时文件
            boolean delete = file.delete();
            if (!delete) {
                log.error("file delete error,filepath" + file.getAbsolutePath());
            }
        }
    }

    /**
     * 检测后缀是否为空
     * @param uploadPath
     * @param originalFilename
     * @return
     */
    private String ensureUploadPathHasSuffix(String uploadPath, String originalFilename) {
        String suffix = FileUtil.getSuffix(originalFilename);
        if (StrUtil.isNotBlank(suffix) && IMAGE_SUFFIXES.contains(suffix.toLowerCase())) {
            return uploadPath;
        }
        // 默认添加 jpg 后缀
        return uploadPath + ".jpg";
    }
}
