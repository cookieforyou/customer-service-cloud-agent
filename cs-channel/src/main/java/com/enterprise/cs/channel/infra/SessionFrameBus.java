package com.enterprise.cs.channel.infra;

import com.enterprise.cs.channel.api.FrameSink;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话帧总线（《08》§4/§5）：按会话维护帧序号、有界补发缓冲（256，《12》§3）与热订阅。
 * M0批3 为单实例内存实现；Redis 化补发缓冲随多实例部署形态落地（M0批5 复盘，偏离已记档）。
 * DONE/ERROR 帧即完成流；此后订阅只走缓冲补发（Last-Event-ID 语义）。
 */
@Component
public class SessionFrameBus {

    static final int BUFFER_MAX = 256;

    private final JsonMapper json;
    private final Map<UUID, SessionStream> streams = new ConcurrentHashMap<>();

    record Frame(String event, long id, String data) {
    }

    static final class SessionStream {
        final AtomicLong seq = new AtomicLong();
        // directBestEffort：sink 不保留历史（补发唯一来源=受控环形缓冲）；
        // 慢消费者丢帧由客户端 Last-Event-ID 重连恢复（《08》§5 设计语义）
        final Sinks.Many<Frame> sink = Sinks.many().multicast().directBestEffort();
        final ArrayDeque<Frame> buffer = new ArrayDeque<>();
    }

    public SessionFrameBus(JsonMapper json) {
        this.json = json;
    }

    /** 绑定 (sessionId, turnId, messageId) 的帧发射端。 */
    public FrameSink sinkFor(UUID sessionId, UUID turnId, UUID messageId) {
        SessionStream s = streams.computeIfAbsent(sessionId, id -> new SessionStream());
        return new FrameSink() {
            @Override
            public void token(String delta) {
                emit(s, "TOKEN", Map.of("delta", delta));
            }

            @Override
            public void message(String role, String text) {
                emit(s, "MESSAGE", Map.of("role", role, "text", text));
            }

            @Override
            public void tool(String toolName, String phase, String summary) {
                emit(s, "TOOL", Map.of("toolName", toolName, "phase", phase, "summary", summary));
            }

            @Override
            public void trace(String evidenceJson) {
                emit(s, "TRACE", Map.of("evidence", evidenceJson));
            }

            @Override
            public void escalation(String stateJson) {
                emit(s, "ESCALATION", Map.of("state", stateJson));
            }

            @Override
            public void done() {
                emit(s, "DONE", Map.of("turnId", turnId.toString(), "messageId", messageId.toString()));
                s.sink.tryEmitComplete();
            }

            @Override
            public void error(String code, String message) {
                emit(s, "ERROR", Map.of("code", code, "message", message));
                s.sink.tryEmitComplete();
            }
        };
    }

    /** 订阅会话流：先补发 afterId 之后的缓冲帧，再接入热流。 */
    public Flux<ServerSentEvent<String>> subscribe(UUID sessionId, long afterId) {
        SessionStream s = streams.get(sessionId);
        if (s == null) {
            return Flux.empty();
        }
        List<Frame> replay;
        synchronized (s.buffer) {
            replay = s.buffer.stream().filter(f -> f.id() > afterId).toList();
        }
        return Flux.concat(Flux.fromIterable(replay), s.sink.asFlux().filter(f -> f.id() > afterId))
                .map(f -> ServerSentEvent.<String>builder()
                        .id(Long.toString(f.id()))
                        .event(f.event())
                        .data(f.data())
                        .build());
    }

    private void emit(SessionStream s, String event, Object payload) {
        String data;
        try {
            data = json.writeValueAsString(payload);
        } catch (Exception e) {
            data = "{}";
        }
        Frame frame = new Frame(event, s.seq.incrementAndGet(), data);
        synchronized (s.buffer) {
            s.buffer.addLast(frame);
            while (s.buffer.size() > BUFFER_MAX) {
                s.buffer.pollFirst();
            }
        }
        s.sink.tryEmitNext(frame);
    }
}
