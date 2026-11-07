package com.panic.core.agent.memory;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatTurnRepository extends JpaRepository<ChatTurn, Long> {

	/** Most recent turns first (to apply the window), newest to oldest. */
	List<ChatTurn> findByConversation_IdOrderByIdDesc(String conversationId, Pageable pageable);

	List<ChatTurn> findByConversation_IdOrderByIdAsc(String conversationId);

	/** Cascade helper: deleting a conversation removes its turns first. */
	void deleteByConversation_Id(String conversationId);
}
