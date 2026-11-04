package com.panic.core.agent.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** One chat conversation; {@code id} is a UUID string shared with the client. */
@Entity
@Table(name = "conversations")
@Getter
@Setter
public class Conversation {

	@Id
	@Column(length = 64)
	private String id;

	@Column(nullable = false)
	private LocalDateTime createdAt = LocalDateTime.now();
}
