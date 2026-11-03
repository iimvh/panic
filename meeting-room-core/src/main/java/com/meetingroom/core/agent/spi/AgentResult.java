package com.meetingroom.core.agent.spi;

/**
 * Result of one agent invocation. {@code ok=false} means the error was captured as text so the
 * parent agent can decide how to answer; errors that abort the pipeline still throw.
 */
public record AgentResult(String agentId, String text, boolean ok, String error, int depth) {

	public static AgentResult ok(String agentId, String text, int depth) {
		return new AgentResult(agentId, text == null ? "" : text, true, null, depth);
	}

	public static AgentResult failed(String agentId, String error, int depth) {
		return new AgentResult(agentId, "", false, error, depth);
	}
}
