-- M0批2 落位；2026-09-27 热修重构（坑#25）：cs_app 角色创建属实例级权限（CREATEROLE），
-- 应用账号在受控环境（ECS）不具备——本迁移能力自适应：当前账号可建则自建（dev/测试
-- 超管，零运维步骤），不可建则跳过并交由 deploy/db/bootstrap-roles.sql 运维引导；
-- 授权仅在角色存在时执行。应用运行期租户隔离 = V1 FORCE RLS + 每事务
-- set_config('cs.tenant_id',…,true)（应用侧 TenantContext，《12》§2.2），与本角色授权独立。
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'cs_app') THEN
        BEGIN
            CREATE ROLE cs_app NOLOGIN;
        EXCEPTION WHEN insufficient_privilege THEN
            RAISE NOTICE '当前账号无 CREATEROLE：跳过 cs_app 创建（生产由运维执行 deploy/db/bootstrap-roles.sql）';
        END;
    END IF;

    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'cs_app') THEN
        GRANT USAGE ON SCHEMA public TO cs_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO cs_app;
        -- 覆盖后续迁移新增表（default privileges 按 grantor 生效，本迁移以应用账号执行）
        ALTER DEFAULT PRIVILEGES IN SCHEMA public
            GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cs_app;
        -- 迁移历史表不授予应用写权限
        REVOKE ALL ON flyway_schema_history FROM cs_app;
    ELSE
        RAISE NOTICE 'cs_app 未就位：跳过授权（应用以表 owner 运行仍受 FORCE RLS 约束，租户 GUC 由应用注入）';
    END IF;
END $$;
