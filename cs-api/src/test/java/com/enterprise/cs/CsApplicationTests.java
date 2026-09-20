package com.enterprise.cs;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * M0批1 骨架冒烟 + M0批2 起含持久层：全上下文装配（含 Flyway 迁移于真实 PG）可启动。
 */
@SpringBootTest
@Testcontainers
class CsApplicationTests {

    /** 与 ConversationPersistenceIntegrationTest 同镜像（pgvector/pgvector:pg17，本机已缓存）。TC 2.x：非泛型。 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17"));

    @Test
    void contextLoads() {
        // 全装配冒烟：DataSource + Flyway V1/V2 + JPA validate 通过即绿
    }
}
