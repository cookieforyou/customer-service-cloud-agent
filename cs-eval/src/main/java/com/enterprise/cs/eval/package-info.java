/**
 * CSCA eval 模块（cs-eval，限界上下文）。
 * 评测执行——golden set 回归、promptfoo 数据源、在线 judge、合规题库 runner。
 * 包结构约定：api（对外契约）/ domain / app / infra（模块私有）——跨模块仅可依赖本包 api（《02》§3）。
 */
package com.enterprise.cs.eval;
