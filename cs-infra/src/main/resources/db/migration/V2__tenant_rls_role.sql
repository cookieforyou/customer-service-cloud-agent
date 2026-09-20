-- M0批2：租户隔离执行角色与应用授权（《12》§2 落位约定）
-- cs_app = 应用执行角色（NOLOGIN 基线）；生产登录口令由运维 ALTER ROLE cs_app LOGIN PASSWORD '...' 配置（部署手册，不落库）
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'cs_app') THEN
        CREATE ROLE cs_app NOLOGIN;
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO cs_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO cs_app;
-- 覆盖后续迁移新增表（同 owner 创建）
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cs_app;
-- 迁移历史表不授予应用写权限
REVOKE ALL ON flyway_schema_history FROM cs_app;
