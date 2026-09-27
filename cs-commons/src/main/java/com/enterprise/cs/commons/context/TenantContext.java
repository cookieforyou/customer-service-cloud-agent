package com.enterprise.cs.commons.context;

/**
 * 租户上下文（《12》§2.2 RLS 运行期接线，坑#25/O-1 前移）：channel 侧从身份 claims 置入
 * （HTTP 线程各入口 + 轮次执行线程），conversation 事务内据此 `set_config('cs.tenant_id',…,true)`
 * 注入会话级 GUC——表 owner 非 superuser 时 FORCE RLS fail-closed，无 GUC 即无数据访问。
 * ThreadLocal 跨模块同线程传递（不进跨模块契约）；缺失时跳过注入，交由 RLS fail-closed 兜底。
 */
public final class TenantContext {

    private static final ThreadLocal<String> TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(String tenantId) {
        TENANT.set(tenantId);
    }

    public static String get() {
        return TENANT.get();
    }

    public static void clear() {
        TENANT.remove();
    }
}
