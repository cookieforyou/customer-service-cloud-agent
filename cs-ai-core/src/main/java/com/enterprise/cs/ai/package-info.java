/**
 * CSCA ai-core 模块（cs-ai-core，限界上下文）。
 * AI 内核——模型分级路由 RoutingChatModel、ChatClient 工厂、Advisor 护栏链、Prompt 仓库、语义缓存。
 * 包结构约定：api（对外契约）/ domain / app / infra（模块私有）——跨模块仅可依赖本包 api（《02》§3）。
 */
package com.enterprise.cs.ai;
