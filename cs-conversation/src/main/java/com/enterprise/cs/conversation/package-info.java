/**
 * CSCA conversation 模块（cs-conversation，限界上下文）。
 * 会话与上下文——会话状态机、事件溯源、三层记忆组装、澄清与超时唤醒。
 * 包结构约定：api（对外契约）/ domain / app / infra（模块私有）——跨模块仅可依赖本包 api（《02》§3）。
 */
package com.enterprise.cs.conversation;
