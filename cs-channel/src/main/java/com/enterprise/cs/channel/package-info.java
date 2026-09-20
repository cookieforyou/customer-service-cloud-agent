/**
 * CSCA channel 模块（cs-channel，限界上下文）。
 * 渠道接入——ChannelAdapter SPI、webchat/openapi 适配器、SSE 帧协议、幂等与限流。
 * 包结构约定：api（对外契约）/ domain / app / infra（模块私有）——跨模块仅可依赖本包 api（《02》§3）。
 */
package com.enterprise.cs.channel;
