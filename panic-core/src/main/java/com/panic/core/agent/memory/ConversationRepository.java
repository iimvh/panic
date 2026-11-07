package com.panic.core.agent.memory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, String> {

	/** Sidebar listing: the user's conversations, latest activity first. */
	List<Conversation> findByUserIdOrderByLastActivityAtDesc(String userId);
}
