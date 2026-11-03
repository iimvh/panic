package com.meetingroom.core.agent.spi;

/**
 * Runtime limits of an agent. Values must be valid (positive) when a definition is created.
 */
public record AgentPolicy(int maxDepth, long timeoutMs, int maxToolCalls) {

	public static final AgentPolicy DEFAULT = new AgentPolicy(5, 120_000L, 8);

	public AgentPolicy {
		if (maxDepth < 0) {
			throw new IllegalArgumentException("maxDepth must be >= 0");
		}
		if (timeoutMs <= 0) {
			throw new IllegalArgumentException("timeoutMs must be > 0");
		}
		if (maxToolCalls <= 0) {
			throw new IllegalArgumentException("maxToolCalls must be > 0");
		}
	}
}
