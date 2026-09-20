package com.enterprise.cs.conversation;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 模块级集成测试装配（模块无启动入口，《13》§3 模块切片样板）：
 * 扫描 conversation 包（实体/仓储），DataSource/Flyway/JPA 走自动装配——
 * 迁移经 cs-infra 依赖进入类路径（classpath:db/migration）。仅测试源集。
 */
@SpringBootApplication
class ConversationTestApp {
}
