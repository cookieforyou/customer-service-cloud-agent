package com.enterprise.cs.channel.infra;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** M0批3：帧总线（序号单调/Last-Event-ID 补发/缓冲上界）单元测试。 */
class SessionFrameBusTest {

    private final SessionFrameBus bus = new SessionFrameBus(new JsonMapper());

    @Test
    void replayAfterLastEventIdAndCompleteOnDone() {
        UUID session = UUID.randomUUID();
        var sink = bus.sinkFor(session, UUID.randomUUID(), UUID.randomUUID());
        for (int i = 0; i < 5; i++) {
            sink.token("t" + i);
        }
        sink.done();

        List<ServerSentEvent<String>> all = bus.subscribe(session, 0L)
                .collectList().block(Duration.ofSeconds(5));
        assertThat(all).hasSize(6);
        assertThat(all.get(0).event()).isEqualTo("TOKEN");
        assertThat(all.get(5).event()).isEqualTo("DONE");
        for (int i = 0; i < all.size(); i++) {
            assertThat(all.get(i).id()).isEqualTo(String.valueOf(i + 1));  // 序号从 1 单调递增
        }

        List<ServerSentEvent<String>> after2 = bus.subscribe(session, 2L)
                .collectList().block(Duration.ofSeconds(5));
        assertThat(after2).hasSize(4);
        assertThat(after2.get(0).id()).isEqualTo("3");  // 只补发 id>2
    }

    @Test
    void bufferEvictsOldestBeyondBound() {
        UUID session = UUID.randomUUID();
        var sink = bus.sinkFor(session, UUID.randomUUID(), UUID.randomUUID());
        for (int i = 0; i < 300; i++) {
            sink.token("x");
        }
        List<ServerSentEvent<String>> head = bus.subscribe(session, 0L)
                .take(1).collectList().block(Duration.ofSeconds(5));
        // 300 帧仅留最近 256：最早可补发帧 id = 300-256+1 = 45
        assertThat(head.get(0).id()).isEqualTo("45");
    }
}
