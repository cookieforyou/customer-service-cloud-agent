package com.enterprise.cs.conversation.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SessionEventRepository extends JpaRepository<SessionEvent, UUID> {

    Optional<SessionEvent> findFirstBySessionIdOrderBySeqDesc(UUID sessionId);
}
