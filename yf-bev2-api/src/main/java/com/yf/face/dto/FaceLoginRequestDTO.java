package com.yf.face.dto;
import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
/**
 * 人脸识别登录请求DTO
 */
@Data
public class FaceLoginRequestDTO {

    /**
     * 登录账号
     */
    @NotBlank(message = "账号不能为空")
    private String account;

    /**
     * 登录密码
     */
    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * 身份证号
     */
    @NotBlank(message = "身份证号不能为空")
    @Pattern(regexp = "(^\\d{18}$)|(^\\d{17}(\\d|X|x)$)", message = "请输入正确的18位身份证号")
    private String idCard;

    /**
     * 人脸图片Base64编码（去除前缀）
     */
    @NotBlank(message = "人脸照片不能为空")
    private String faceImageBase64;
}