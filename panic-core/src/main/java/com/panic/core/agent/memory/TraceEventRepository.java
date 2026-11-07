package com.panic.core.agent.memory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TraceEventRepository extends JpaRepository<TraceEvent, Long> {

	List<TraceEvent> findByConversationIdOrderByTraceIdAscSequenceAsc(String conversationId);

	List<TraceEvent> findByTraceIdOrderBySequenceAsc(Long traceId);

	boolean existsByTraceId(Long traceId);

	void deleteByConversationId(String conversationId);
}
