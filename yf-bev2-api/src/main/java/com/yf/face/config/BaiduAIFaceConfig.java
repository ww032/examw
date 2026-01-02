package com.yf.face.config;

import com.baidu.aip.face.AipFace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 百度AI人脸接口配置类
 */
@Configuration
public class BaiduAIFaceConfig {

    @Value("${baidu.ai.face.app-id}")
    private String appId;

    @Value("${baidu.ai.face.api-key}")
    private String apiKey;

    @Value("${baidu.ai.face.secret-key}")
    private String secretKey;

    /**
     * 初始化百度人脸客户端（单例）
     */
    @Bean
    public AipFace aipFace() {
        AipFace client = new AipFace(appId, apiKey, secretKey);
        // 可选：设置网络连接参数
        client.setConnectionTimeoutInMillis(2000);
        client.setSocketTimeoutInMillis(60000);
        // 可选：设置代理服务器地址（如需）
        // client.setHttpProxy("proxy_host", proxy_port);
        // client.setHttpsProxy("proxy_host", proxy_port);
        return client;
    }
}