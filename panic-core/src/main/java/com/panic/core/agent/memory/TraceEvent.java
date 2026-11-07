package com.panic.core.agent.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One persisted {@link com.panic.pipeline.PipelineEvent} of a conversation's turn, so a
 * session can be replayed (answer + execution log) from any browser after the stream is gone.
 * Rows are written in one batch when the turn finishes — never touched while the trace runs.
 */
@Entity
@Table(name = "trace_events", indexes = {
		@Index(name = "trace_events_conversation_idx", columnList = "conversation_id, trace_id"),
		@Index(name = "trace_events_trace_idx", columnList = "trace_id") })
@Getter
@Setter
public class TraceEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** Conversation the traced turn belongs to (raw id, no FK: traces outlive nothing). */
	@Column(name = "conversation_id", nullable = false, length = 64)
	private String conversationId;

	/** Pipeline trace id; pairs with {@link ChatTurn#getTraceId()}. */
	@Column(name = "trace_id", nullable = false)
	private Long traceId;

	/** Position of the event inside its trace (the order it was recorded in). */
	@Column(nullable = false)
	private int sequence;

	/** Id of the pipeline event within its trace; {@code parentId} refers to these. */
	@Column(name = "event_id", nullable = false)
	private Long eventId;

	@Column(nullable = false, length = 16)
	private String phase;

	@Column(nullable = false, length = 16)
	private String status;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String detail;

	/** When the event started, as epoch milliseconds (timestamps stay timezone-free). */
	@Column(name = "started_at", nullable = false)
	private long startedAt;

	/** Round-trip time when the event closed; null while/for events that never close. */
	@Column(name = "duration_ms")
	private Long durationMs;

	/** {@link #eventId} of the parent event; equal to {@code eventId} for roots. */
	@Column(name = "parent_id")
	private Long parentId;

	/** Conversation role of the event (system/user/assistant/tool/context); may be null. */
	@Column(length = 16)
	private String role;

	/** Agent that produced the event; null for request-level events. */
	@Column(name = "agent_id", length = 64)
	private String agentId;

	/** Lane the event was recorded on (the emitting agent or request-level actor). */
	@Column(length = 64)
	private String actor;

	/** recursion depth the event was recorded at (root = 0). */
	@Column(nullable = false)
	private int depth;
}
