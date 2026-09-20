package com.enterprise.cs.conversation;

import com.enterprise.cs.conversation.domain.Message;
import com.enterprise.cs.conversation.domain.MessageRepository;
import com.enterprise.cs.conversation.domain.Session;
import com.enterprise.cs.conversation.domain.SessionEventRepository;
import com.enterprise.cs.conversation.domain.SessionRepository;
import com.enterprise.cs.conversation.domain.TurnRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M0批2 数据基线集成测试（《13》§3 Testcontainers 样板）：
 * ① Flyway V1/V2 迁移在真实 PG 上可重放（容器净起 + Hibernate validate 双重校验）；
 * ② RLS 跨租户拦截：cs_app 角色下读过滤/写拒绝（fail-closed）；
 * ③ JPA 实体映射往返 + 幂等唯一键查询。
 * RLS 验证方式：事务绑定连接内 SET ROLE cs_app（current_user 变为非超级角色后 RLS 生效），
 * GUC 与数据语句走同一条 Connection（避免嵌套 JdbcTemplate 取到另一条连接），用毕 RESET ROLE。
 */
@SpringBootTest
@Testcontainers
class ConversationPersistenceIntegrationTest {

    /** 测试镜像：pgvector/pgvector:pg17——与 ECS 既有 PG(with vector) 形态一致、本机已缓存（姊妹项目同款）；pg17 为小版本浮动 tag，测试镜像低风险，精确钉版经复盘再定。 */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17"));

    private static final String INSERT_SESSION =
            "INSERT INTO cs_session (id, tenant_id, channel, state, created_at, last_active_at) "
                    + "VALUES (?, ?, 'webchat', 'ACTIVE', now(), now())";

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private MessageRepository messages;

    @Autowired
    private TurnRepository turns;

    @Autowired
    private SessionEventRepository events;

    @Autowired
    private com.enterprise.cs.conversation.api.ConversationPort conversation;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    void jpaRoundTripAndIdempotencyKey() {
        UUID sessionId = UUID.randomUUID();
        sessions.saveAndFlush(new Session(sessionId, "t-a", "webchat", "v-a", "ACTIVE", Instant.now()));

        assertThat(sessions.findById(sessionId))
                .hasValueSatisfying(s -> {
                    assertThat(s.getTenantId()).isEqualTo("t-a");
                    assertThat(s.getChannel()).isEqualTo("webchat");
                    assertThat(s.getVisitorId()).isEqualTo("v-a");
                });
        assertThat(messages.existsByTenantIdAndChannelAndChannelMsgId("t-a", "webchat", "m-1")).isFalse();

        UUID msgId = UUID.randomUUID();
        messages.saveAndFlush(new Message(msgId, sessionId, null, 1, "USER", "TEXT", "你好", "t-a", "webchat", "m-1", Instant.now()));

        assertThat(messages.existsByTenantIdAndChannelAndChannelMsgId("t-a", "webchat", "m-1")).isTrue();
        assertThat(messages.findByTenantIdAndChannelAndChannelMsgId("t-a", "webchat", "m-1"))
                .hasValueSatisfying(m -> assertThat(m.getContent()).isEqualTo("你好"));
    }

    /** M0批4：V3 事件表迁移 + 轮次生命周期（start/finish）+ 窗口读拼（排除当前消息、旧→新）。 */
    @Test
    void turnLifecyclePersistsAndWindowReadAssembles() {
        UUID sessionId = UUID.randomUUID();
        sessions.saveAndFlush(new Session(sessionId, "t-a", "webchat", "v-1", "ACTIVE", Instant.now()));
        UUID inboundId = UUID.randomUUID();
        messages.saveAndFlush(new Message(inboundId, sessionId, null, 1, "USER", "TEXT",
                "退款怎么办理", "t-a", "webchat", "c-turn-1", Instant.now()));

        UUID turnId = UUID.randomUUID();
        conversation.startTurn(new com.enterprise.cs.conversation.api.ConversationPort.StartTurnCmd(
                sessionId, turnId, inboundId, "T1", "{\"decision\":\"direct\",\"tier\":\"T1\"}"));

        assertThat(turns.findById(turnId)).hasValueSatisfying(t -> {
            assertThat(t.getState()).isEqualTo("RUNNING");
            assertThat(t.getSeq()).isEqualTo(1);
            assertThat(t.getModelTier()).isEqualTo("T1");
            assertThat(t.getRouteDecision()).contains("direct");
        });
        assertThat(messages.findById(inboundId)).hasValueSatisfying(m -> assertThat(m.getTurnId()).isEqualTo(turnId));

        UUID aiId = conversation.finishTurn(new com.enterprise.cs.conversation.api.ConversationPort.FinishTurnCmd(
                turnId, "COMPLETED", 11, 7, 12, "退款会在1-3个工作日原路退回"));

        assertThat(turns.findById(turnId)).hasValueSatisfying(t -> {
            assertThat(t.getState()).isEqualTo("COMPLETED");
            assertThat(t.getTokensIn()).isEqualTo(11);
            assertThat(t.getTokensOut()).isEqualTo(7);
            assertThat(t.getLatencyMs()).isEqualTo(12);
        });
        assertThat(messages.findById(aiId)).hasValueSatisfying(m -> {
            assertThat(m.getRole()).isEqualTo("AI");
            assertThat(m.getContent()).isEqualTo("退款会在1-3个工作日原路退回");
            assertThat(m.getTurnId()).isEqualTo(turnId);
            assertThat(m.getTenantId()).isEqualTo("t-a");
        });

        List<String> eventTypes = jdbc.queryForList(
                "SELECT event_type FROM cs_session_event WHERE session_id = ? ORDER BY seq",
                String.class, sessionId);
        assertThat(eventTypes).containsExactly("ROUTE_DECIDED", "MESSAGE_APPENDED");

        // 第二轮：窗口读拼排除当前入站消息，旧→新含第一轮 user+AI
        UUID inbound2 = UUID.randomUUID();
        messages.saveAndFlush(new Message(inbound2, sessionId, null, 3, "USER", "TEXT",
                "多久到账", "t-a", "webchat", "c-turn-2", Instant.now()));
        var window = conversation.recentWindow(sessionId, 10, inbound2);
        assertThat(window).hasSize(2);
        assertThat(window.get(0).role()).isEqualTo("USER");
        assertThat(window.get(0).content()).isEqualTo("退款怎么办理");
        assertThat(window.get(1).role()).isEqualTo("AI");
        assertThat(window.get(1).content()).isEqualTo("退款会在1-3个工作日原路退回");
    }

    @Test
    void rlsBlocksCrossTenantAccess() {
        UUID rowA = UUID.randomUUID();

        // ① 租户 t-a（cs_app 角色）：写入成功
        asTenant("t-a", con -> update(con, INSERT_SESSION, rowA, "t-a"));
        int visibleInA = asTenant("t-a", con -> count(con, "SELECT count(*) FROM cs_session WHERE tenant_id = 't-a'"));
        assertThat(visibleInA).isEqualTo(1);

        // ② 租户 t-b（cs_app 角色）：读过滤为空（含点名 t-a 行）——RLS 生效
        int visibleInB = asTenant("t-b", con -> count(con, "SELECT count(*) FROM cs_session"));
        int explicitAInB = asTenant("t-b", con -> count(con, "SELECT count(*) FROM cs_session WHERE tenant_id = 't-a'"));
        assertThat(visibleInB).isZero();
        assertThat(explicitAInB).isZero();

        // ③ 租户 t-b 写 t-a 的行：WITH CHECK 拒绝
        assertThatThrownBy(() -> asTenant("t-b", con -> update(con, INSERT_SESSION, UUID.randomUUID(), "t-a")))
                .hasMessageContaining("row-level security");

        // ④ fail-closed：cs_app 角色但不设租户上下文，写入被拒
        assertThatThrownBy(() -> asRoleWithoutTenant(con -> update(con, INSERT_SESSION, UUID.randomUUID(), "t-a")))
                .hasMessageContaining("row-level security");

        // ⑤ 管理连接（超级用户）可见该行——证明 ② 的 0 行是 RLS 过滤而非数据缺失
        try {
            Connection admin = DataSourceUtils.getConnection(dataSource());
            try {
                assertThat(count(admin, "SELECT count(*) FROM cs_session WHERE tenant_id = 't-a'")).isEqualTo(1);
            } finally {
                DataSourceUtils.releaseConnection(admin, dataSource());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("admin count failed", e);
        }
    }

    /** 在独立事务内以 cs_app 角色 + 指定租户 GUC 执行；GUC/数据语句同一 Connection；用毕 RESET ROLE。 */
    private <T> T asTenant(String tenantId, SqlWork<T> work) {
        return inTx((con, st) -> {
            st.execute("SET LOCAL cs.tenant_id = '" + tenantId + "'");
            return work.run(con);
        });
    }

    private void asRoleWithoutTenant(SqlWorkVoid work) {
        inTx((con, st) -> {
            work.run(con);
            return null;
        });
    }

    private <T> T inTx(RoleWork<T> work) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Connection con = DataSourceUtils.getConnection(dataSource());
            try (Statement st = con.createStatement()) {
                st.execute("SET ROLE cs_app");
                try {
                    result.set(work.run(con, st));
                } catch (RuntimeException e) {
                    failure.set(e);
                } catch (SQLException e) {
                    failure.set(new org.springframework.jdbc.UncategorizedSQLException("rls work", null, e));
                } finally {
                    resetRoleQuietly(st);
                }
            } catch (SQLException e) {
                failure.compareAndSet(null, new org.springframework.jdbc.UncategorizedSQLException("rls setup", null, e));
            } finally {
                DataSourceUtils.releaseConnection(con, dataSource());
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private javax.sql.DataSource dataSource() {
        return ((org.springframework.orm.jpa.JpaTransactionManager) txManager).getDataSource();
    }
    private static void resetRoleQuietly(Statement st) {
        try {
            st.execute("RESET ROLE");
        } catch (SQLException ignored) {
            // abort 事务上的 RESET 必然 25P02：吞掉次生异常，保留原始异常；连接随事务回滚复位
        }
    }

    private static int update(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        }
    }

    private static int count(Connection con, String sql) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection con) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlWorkVoid {
        void run(Connection con) throws SQLException;
    }

    @FunctionalInterface
    private interface RoleWork<T> {
        T run(Connection con, Statement st) throws SQLException;
    }
}
