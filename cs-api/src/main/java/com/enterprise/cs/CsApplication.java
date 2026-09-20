package com.enterprise.cs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * CSCA 唯一部署入口（模块化单体：cs-api 组装全部上下文模块，《01》D-02）。
 * 阶段：M0批3——webchat 渠道 + 双 issuer JWT + SSE 帧 v1；编排引擎 echo 桩（M0批4 接管）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.enterprise.cs")
public class CsApplication {

    public static void main(String[] args) {
        SpringApplication.run(CsApplication.class, args);
    }
}
