package com.enterprise.cs;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** M0批1 骨架冒烟：空上下文可启动（12 模块 reactor 全绿为 CI 门禁）。 */
@SpringBootTest
class CsApplicationTests {

    @Test
    void contextLoads() {
        // 空上下文冒烟——业务装配自 M0批2 起逐批进入
    }
}
