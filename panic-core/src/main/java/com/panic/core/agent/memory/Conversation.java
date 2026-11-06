package com.panic.core.agent.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** One chat conversation; {@code id} is a UUID string shared with the client. */
@Entity
@Table(name = "conversations", indexes = @Index(name = "conversations_user_activity_idx",
		columnList = "user_id, last_activity_at"))
@Getter
@Setter
public class Conversation {

	@Id
	@Column(length = 64)
	private String id;

	@Column(nullable = false)
	private LocalDateTime createdAt = LocalDateTime.now();

	/** Sidebar title; filled from the first user message when left blank. */
	@Column(length = 120)
	private String title;

	/** Sidebar ordering stamp; bumped every time the conversation is used. */
	@Column(name = "last_activity_at", nullable = false)
	private LocalDateTime lastActivityAt = LocalDateTime.now();

	/** Workspace owner until real auth arrives; everyone shares the "local" account. */
	@Column(name = "user_id", nullable = false, length = 32)
	private String userId = "local";
}
