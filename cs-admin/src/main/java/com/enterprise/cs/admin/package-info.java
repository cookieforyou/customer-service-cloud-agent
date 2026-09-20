/**
 * CSCA admin 模块（cs-admin，限界上下文）。
 * 管理后台 API 聚合——Agent/工具/FAQ 配置、审计查询、知识缺口、看板数据。
 * 包结构约定：api（对外契约）/ domain / app / infra（模块私有）——跨模块仅可依赖本包 api（《02》§3）。
 */
package com.enterprise.cs.admin;
