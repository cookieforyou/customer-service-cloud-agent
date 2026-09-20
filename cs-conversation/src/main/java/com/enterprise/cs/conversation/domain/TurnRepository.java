package com.enterprise.cs.conversation.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** 轮次仓储（《12》§2.1）。阶段：M0批2 数据基线。 */
public interface TurnRepository extends JpaRepository<Turn, UUID> {
}
