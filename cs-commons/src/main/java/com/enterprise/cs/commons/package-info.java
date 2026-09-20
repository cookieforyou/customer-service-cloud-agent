/**
 * CSCA commons 模块（cs-commons，限界上下文）。
 * 跨模块契约基座——统一响应信封、错误码、事件契约 DTO、共享常量收敛。
 * 包结构约定：api（对外契约）/ domain / app / infra（模块私有）——跨模块仅可依赖本包 api（《02》§3）。
 */
package com.enterprise.cs.commons;
