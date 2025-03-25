package com.yupi.yupicturebackend.manager;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.unit.DataUnit;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpStatus;
import cn.hutool.http.HttpUtil;
import cn.hutool.http.Method;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import com.qcloud.cos.model.ciModel.persistence.OriginalInfo;
import com.qcloud.cos.utils.StringUtils;
import com.yupi.yupicturebackend.common.ResultUtils;
import com.yupi.yupicturebackend.config.CosClientConfig;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.exception.ErrorCode;
import com.yupi.yupicturebackend.exception.ThrowUtils;
import com.yupi.yupicturebackend.model.dto.file.UploadPictureResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.invoke.MutableCallSite;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

@Slf4j
@Component
@Deprecated
public class FileManager {
    @Resource
    private CosManager cosManager;
    @Resource
    private CosClientConfig cosClientConfig;

    /**
     * 上传图片
     */
    public UploadPictureResult uploadPicture(MultipartFile multipartFile, String uploadPathPrefix) {
        //校验图片
        validPicture(multipartFile);
        //图片上传地址
        String uuid = RandomUtil.randomString(16);
        String originalFilename = multipartFile.getOriginalFilename();
        //拼接文件名称
        String uploadFileName = String.format("%s_%s.%s", DateUtil.formatDate(new Date())
                , uuid, FileUtil.getSuffix(originalFilename));
        String uploadPath = String.format("/%s/%s", uploadPathPrefix, uploadFileName);

        //解析结果并返回
        File file = null;
        try {
            //上传文件
            file = File.createTempFile(uploadPath, null);
            multipartFile.transferTo(file);
            PutObjectResult putObjectResult = cosManager.putPictureObject(uploadPath, file);
            //获取图片信息对象
            ImageInfo imageInfo = putObjectResult.getCiUploadResult().getOriginalInfo().getImageInfo();
            // 封装返回结果
            UploadPictureResult uploadPictureResult = new UploadPictureResult();
            uploadPictureResult.setUrl(cosClientConfig.getHost() + "/" + uploadPath);
            uploadPictureResult.setPicName(FileUtil.mainName(originalFilename));
            uploadPictureResult.setPicSize(FileUtil.size(file));
            uploadPictureResult.setPicWidth(imageInfo.getWidth());
            uploadPictureResult.setPicHeight(imageInfo.getHeight());
            uploadPictureResult.setPicScale(NumberUtil.round(imageInfo.getWidth() * 1.0 / imageInfo.getHeight(),2).doubleValue());
            uploadPictureResult.setPicFormat(imageInfo.getFormat());

            return uploadPictureResult;
        } catch (IOException e) {
            log.error("图片上传到存储对象失败" , e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"上传失败");

        } finally {
            //清理临时文件
            deleteTemplateFIle(file);

        }



    }
    /**
     *  图片校验
     * @param multipartFile
     */
    private void validPicture(MultipartFile multipartFile){
        ThrowUtils.throwIf(multipartFile == null, ErrorCode.PARAMS_ERROR, "文件不能为空");
        //1.校验大小
        long fileSize = multipartFile.getSize();
        final long TWO_MB = 2048 * 2048;
        ThrowUtils.throwIf(fileSize > TWO_MB, ErrorCode.PARAMS_ERROR, "图片大小不能超过2MB");

        //2.校验文件格式
        String fileSuffix = FileUtil.getSuffix(multipartFile.getOriginalFilename());
        //允许上传的文件后缀。
        final List<String> ALLOW_FORMAT_LIST = Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "tiff", "webp", "svg");
        ThrowUtils.throwIf(!ALLOW_FORMAT_LIST.contains(fileSuffix), ErrorCode.PARAMS_ERROR, "文件类型错误");
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
}
