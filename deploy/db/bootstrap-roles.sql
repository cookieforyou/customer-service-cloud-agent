-- CSCA 数据库角色引导（运维一次性执行，坑#25）：
--   psql "<管理员连接串，需 CREATEROLE>" -f deploy/db/bootstrap-roles.sql
-- 背景：cs_app 执行角色的创建属实例级权限（CREATEROLE），应用账号（亦为 Flyway 执行者）
-- 不具备——角色引导从应用迁移（V2）剥离至此。幂等，可重复执行；建议先于应用首次启动
-- （先行则 V2 迁移直接完成授权；后行则执行本脚本补授权——含未来迁移新表请重跑一次）。
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'cs_app') THEN
        CREATE ROLE cs_app NOLOGIN;
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO cs_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO cs_app;
-- 注：default privileges 按 grantor 生效——本脚本以管理员执行仅覆盖管理员所建表；
-- 应用后续迁移新表的授权由 V2（应用账号执行）的 default privileges 覆盖。
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cs_app;
REVOKE ALL ON flyway_schema_history FROM cs_app;

-- 启用应用执行角色登录（M1 切换应用连接账号时执行，口令经环境注入不落库）：
-- ALTER ROLE cs_app LOGIN PASSWORD '...';
