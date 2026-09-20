package com.enterprise.cs.channel.api;

/**
 * SSE 帧协议 v1 发射端（《08》§4 帧族唯一权威定义的实现面）。
 * 各方法参数即帧 payload 字段；帧序号/事件名由 SessionFrameBus 统一编码。
 */
public interface FrameSink {

    /** TOKEN 帧：{delta} 增量文本。 */
    void token(String delta);

    /** MESSAGE 帧：{role, text} 整帧消息（兜底话术/坐席消息复用）。 */
    void message(String role, String text);

    /** TOOL 帧：{toolName, phase, summary}（M2批3 起使用）。 */
    default void tool(String toolName, String phase, String summary) {
    }

    /** TRACE 帧：检索证据 JSON（《05》§3 起使用）。 */
    default void trace(String evidenceJson) {
    }

    /** ESCALATION 帧：转人工状态（M2批2 起使用）。 */
    default void escalation(String stateJson) {
    }

    /** DONE 帧：{turnId, messageId}，此后流完成。 */
    void done();

    /** ERROR 帧：{code, message}，此后流完成。 */
    void error(String code, String message);
}
