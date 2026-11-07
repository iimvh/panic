package com.panic.core.agent.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

/** One chat conversation; {@code id} is a UUID string shared with the client. */
@Entity
@Table(name = "conversations", indexes = @Index(name = "conversations_user_activity_idx",
		columnList = "user_id, last_activity_at"))
@Getter
@Setter
public class Conversation {

	/** Workspace owner until real auth arrives; everyone shares the "local" account. */
	public static final String LOCAL_USER = "local";

	@Id
	@Column(length = 64)
	private String id;

	@Column(nullable = false)
	private LocalDateTime createdAt = LocalDateTime.now();

	/** Sidebar title; filled from the first user message when left blank. */
	@Column(length = 120)
	private String title;

	/** Sidebar ordering stamp; bumped every time the conversation is used. */
	// The default lets ddl-auto=update add this NOT NULL column to a populated table (existing
	// rows are stamped with the migration moment instead of failing the copy). @ColumnDefault —
	// unlike columnDefinition — keeps the column type comparable, so update converges instead of
	// re-issuing an illegal "set data type <full definition>" ALTER on every boot.
	@Column(name = "last_activity_at", nullable = false)
	@ColumnDefault("current_timestamp")
	private LocalDateTime lastActivityAt = LocalDateTime.now();

	/** Workspace owner until real auth arrives; everyone shares the "local" account. */
	// Same update-friendliness: legacy conversation rows adopt the "local" owner by default.
	@Column(name = "user_id", nullable = false, length = 32)
	@ColumnDefault("'local'")
	private String userId = LOCAL_USER;
}
