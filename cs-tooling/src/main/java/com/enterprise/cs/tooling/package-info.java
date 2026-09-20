/**
 * CSCA tooling 模块（cs-tooling，限界上下文）。
 * 工具中心——工具注册表、风险分级 L0/L1/L2、MCP client 治理、配额与审批触发。
 * 包结构约定：api（对外契约）/ domain / app / infra（模块私有）——跨模块仅可依赖本包 api（《02》§3）。
 */
package com.enterprise.cs.tooling;
