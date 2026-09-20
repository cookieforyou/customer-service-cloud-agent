/**
 * CSCA orchestration 模块（cs-orchestration，限界上下文）。
 * 编排引擎——Agent 定义与注册、意图路由、TurnPlan 执行、A2A server/client。
 * 包结构约定：api（对外契约）/ domain / app / infra（模块私有）——跨模块仅可依赖本包 api（《02》§3）。
 */
package com.enterprise.cs.orchestration;
