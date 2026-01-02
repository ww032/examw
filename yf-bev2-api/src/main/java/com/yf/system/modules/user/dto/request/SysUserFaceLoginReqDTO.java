package com.yf.system.modules.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 人脸识别登录请求DTO
 */
@Data // 确保Lombok注解生效，生成getter/setter
public class SysUserFaceLoginReqDTO {

    /**
     * 登录账号（与原有登录接口保持一致）
     */
    @NotBlank(message = "账号不能为空")
    private String userName;

    /**
     * 登录密码（与原有登录接口保持一致）
     */
    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * 身份证号（18位校验）
     */
    @NotBlank(message = "身份证号不能为空")
    @Pattern(regexp = "(^\\d{18}$)|(^\\d{17}(\\d|X|x)$)", message = "请输入正确的18位身份证号")
    private String idCard;

    /**
     * 人脸图片Base64编码（去除前缀，仅保留编码内容）
     */
    @NotBlank(message = "人脸照片不能为空")
    private String faceImageBase64;

    /**
     * 图形验证码key（可选，与原有登录接口一致）
     */
    private String captchaKey; // 补充缺失字段

    /**
     * 图形验证码value（可选，与原有登录接口一致）
     */
    private String captchaValue; // 补充缺失字段
}