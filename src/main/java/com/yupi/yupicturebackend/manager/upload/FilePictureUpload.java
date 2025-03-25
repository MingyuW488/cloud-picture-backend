package com.yupi.yupicturebackend.manager.upload;

import cn.hutool.core.io.FileUtil;
import com.yupi.yupicturebackend.exception.ErrorCode;
import com.yupi.yupicturebackend.exception.ThrowUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;


@Slf4j
@Service
public class FilePictureUpload extends PictureUploadTemplate{
    /**
     * 处理文件
     * @param inputSource
     * @param file
     */
    @Override
    protected void processFile(Object inputSource, File file) throws IOException {
        MultipartFile multipartFile = (MultipartFile) inputSource;
        multipartFile.transferTo(file);

    }

    /**
     * 获取文件名称
     * @param inputSource
     * @return
     */
    @Override
    protected String getOriginalFilename(Object inputSource) {
        MultipartFile multipartFile = (MultipartFile) inputSource;
        String originFileName = multipartFile.getOriginalFilename();
        return originFileName;
    }

    /**
     * 文件校验
     * @param inputSource
     */
    @Override
    protected void validPicture(Object inputSource) {

        MultipartFile multipartFile = (MultipartFile) inputSource;
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
}
