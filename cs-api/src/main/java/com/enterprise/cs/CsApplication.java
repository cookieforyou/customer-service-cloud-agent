package com.enterprise.cs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CSCA 唯一部署入口（模块化单体：cs-api 组装全部上下文模块，《01》D-02）。
 * 阶段：M0批1 工程骨架——空上下文冒烟；对话链路自 M0批3/M0批4 起接入。
 */
@SpringBootApplication
public class CsApplication {

    public static void main(String[] args) {
        SpringApplication.run(CsApplication.class, args);
    }
}
