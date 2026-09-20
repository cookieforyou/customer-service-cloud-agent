/**
 * CSCA infra 模块（cs-infra，限界上下文）。
 * 技术基础设施适配——存储客户端（PG/Redis Stack/ES/Milvus/Neo4j/MinIO）、外部系统客户端与观测装配。
 * 包结构约定：api（对外契约）/ domain / app / infra（模块私有）——跨模块仅可依赖本包 api（《02》§3）。
 */
package com.enterprise.cs.infra;
