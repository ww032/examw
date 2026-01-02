package com.yf.plugins.upload.local.service.impl;
import com.yf.ability.Constant;
import com.yf.ability.upload.service.UploadService;
import com.yf.base.api.exception.ServiceException;
import com.yf.base.utils.jackson.JsonHelper;
import com.yf.plugins.upload.local.config.LocalConfig;
import com.yf.plugins.upload.local.dto.UploadRespDTO;
import com.yf.plugins.upload.local.utils.OssUtils;
import com.yf.system.modules.plugin.service.PluginDataService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.codec.binary.Base64;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.multipart.MultipartFile;


import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地上传插件（扩展人脸识别图片处理功能）
 *
 * @author van
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class LocalUpServiceImpl implements UploadService {

    /**
     * 插件唯一标识
     */
    private static final String PLUGIN_CODE = "upload-local";

    /**
     * 人脸识别图片临时存储目录（与普通上传区分，避免混淆）
     */
    private static final String FACE_TEMP_DIR = "/face-temp/";

    /**
     * 支持的人脸图片格式
     */
    private static final String[] SUPPORT_FACE_FORMATS = {"jpg", "jpeg", "png"};

    private final PluginDataService pluginDataService;

    // ===================== 原有核心方法（完全保留，不做修改） =====================
    @Override
    public UploadRespDTO upload(MultipartFile file) {
        // 查找上传配置
        LocalConfig conf = this.getConfig();

        // 上传文件夹
        String fileDir = conf.getLocalDir();

        // 真实物理地址
        String fullPath;

        try {
            // 新文件
            String filePath = OssUtils.processPath(file);
            // 文件保存地址
            fullPath = fileDir + filePath;
            // 创建文件夹
            OssUtils.checkDir(fullPath);
            // 上传文件
            FileCopyUtils.copy(file.getInputStream(), Files.newOutputStream(Paths.get(fullPath)));

            return this.generateResult(conf, filePath);

        } catch (IOException e) {
            log.error(e);
            throw new ServiceException("文件上传失败：" + e.getMessage());
        }
    }

    @Override
    public String upload(String localFile) {
        // 查找上传配置
        LocalConfig conf = this.getConfig();

        // 上传文件夹
        String fileDir = conf.getLocalDir();

        // 真实物理地址
        String fullPath;

        try {
            FileInputStream is = new FileInputStream(localFile);

            // 新文件
            String filePath = OssUtils.renameFile(localFile);
            // 文件保存地址
            fullPath = fileDir + filePath;
            // 创建文件夹
            OssUtils.checkDir(fullPath);
            // 上传文件
            FileCopyUtils.copy(is, Files.newOutputStream(Paths.get(fullPath)));

            return conf.getVisitUrl() + Constant.FILE_PREFIX + filePath;

        } catch (IOException e) {
            log.error(e);
            throw new ServiceException("文件上传失败：" + e.getMessage());
        }
    }

    @Override
    public void download(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 查找上传配置
        LocalConfig conf = this.getConfig();

        // 获取真实的文件路径
        String filePath = this.getRealPath(conf, request.getRequestURI());
        File file = new File(filePath);

        if (!file.exists()) {
            throw new ServiceException("文件不存在！");
        }

        FileInputStream is = null;
        ServletOutputStream os = null;

        try {
            //获取MimeType
            Tika tika = new Tika();
            String mimeType = tika.detect(file);
            response.setContentType(mimeType);
            response.setContentLength((int) file.length());

            is = new FileInputStream(filePath);
            int len = 0;
            byte[] buffer = new byte[1024];
            os = response.getOutputStream();
            while ((len = is.read(buffer)) > 0) {
                os.write(buffer, 0, len);
            }
        } catch (Exception e) {
            log.error(e);
        } finally {
            if (is != null) {
                is.close();
            }
            if (os != null) {
                os.close();
            }
        }
    }

    private LocalConfig getConfig() {
        String str = pluginDataService.findConfig(PLUGIN_CODE);
        return JsonHelper.parseObject(str, LocalConfig.class);
    }

    private UploadRespDTO generateResult(LocalConfig conf, String fileName) {
        //获取加速域名
        String domain = conf.getVisitUrl();

        // 返回结果
        return new UploadRespDTO(domain + Constant.FILE_PREFIX + fileName);
    }

    public String getRealPath(LocalConfig conf, String uri) {
        String regx = Constant.FILE_PREFIX + "(.*)";

        // 查找全部变量
        Pattern pattern = Pattern.compile(regx);
        Matcher m = pattern.matcher(uri);
        if (m.find()) {
            String str = m.group(1);
            return conf.getLocalDir() + str;
        }

        return null;
    }
    // ===================== 新增：适配人脸识别图片的扩展方法 =====================

    /**
     * 接收人脸图片Base64编码，上传到本地临时目录，返回文件物理路径（供百度AI接口读取）
     * @param faceImageBase64 人脸图片Base64编码（已去除前缀）
     * @return 本地临时文件完整路径
     */
    public String uploadFaceImageTemp(String faceImageBase64) {
        // 查找上传配置
        LocalConfig conf = this.getConfig();
        // 构建人脸图片临时存储目录（普通上传目录 + 人脸临时子目录）
        String faceTempFullDir = conf.getLocalDir() + FACE_TEMP_DIR;

        try {
            // 1. Base64解码为字节数组
            byte[] faceImageBytes = Base64.decodeBase64(faceImageBase64);
            if (faceImageBytes.length == 0) {
                throw new ServiceException("人脸图片Base64解码失败，内容为空");
            }

            // 2. 验证图片格式是否支持
            Tika tika = new Tika();
            String mimeType = tika.detect(faceImageBytes);
            String fileFormat = mimeType.substring(mimeType.lastIndexOf("/") + 1).toLowerCase();
            boolean isSupportFormat = false;
            for (String supportFormat : SUPPORT_FACE_FORMATS) {
                if (supportFormat.equals(fileFormat)) {
                    isSupportFormat = true;
                    break;
                }
            }
            if (!isSupportFormat) {
                throw new ServiceException("人脸图片格式不支持，仅支持jpg/jpeg/png");
            }

            // 3. 生成唯一临时文件名（避免重复）
            String tempFileName = "face_" + System.currentTimeMillis() + "." + fileFormat;
            String faceTempFullPath = faceTempFullDir + tempFileName;

            // 4. 创建人脸临时目录（不存在则创建）
            File tempDir = new File(faceTempFullDir);
            if (!tempDir.exists()) {
                boolean mkdirSuccess = tempDir.mkdirs();
                if (!mkdirSuccess) {
                    throw new ServiceException("人脸临时目录创建失败");
                }
            }

            // 5. 将字节数组写入本地临时文件
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(faceImageBytes);
            FileCopyUtils.copy(byteArrayInputStream, Files.newOutputStream(Paths.get(faceTempFullPath)));

            log.info("人脸图片临时上传成功，文件路径：{}", faceTempFullPath);
            return faceTempFullPath;

        } catch (IllegalArgumentException e) {
            log.error("人脸图片Base64格式非法", e);
            throw new ServiceException("人脸图片Base64格式非法，无法解码");
        } catch (IOException e) {
            log.error("人脸图片写入本地临时文件失败", e);
            throw new ServiceException("人脸图片临时存储失败：" + e.getMessage());
        }
    }

    /**
     * 清理人脸识别临时文件（验证完成后调用，避免冗余文件堆积）
     * @param faceTempFilePath 临时文件完整路径
     */
    public void cleanFaceTempFile(String faceTempFilePath) {
        if (faceTempFilePath == null || faceTempFilePath.isEmpty()) {
            return;
        }

        File tempFile = new File(faceTempFilePath);
        if (tempFile.exists() && tempFile.isFile()) {
            try {
                boolean deleteSuccess = tempFile.delete();
                if (deleteSuccess) {
                    log.info("人脸临时文件清理成功，文件路径：{}", faceTempFilePath);
                } else {
                    log.warn("人脸临时文件清理失败，文件路径：{}", faceTempFilePath);
                }
            } catch (SecurityException e) {
                log.error("人脸临时文件清理权限不足", e);
            }
        }
    }
}