package com.meetingroom.core.agent.spi;

import java.util.ArrayList;
import java.util.List;

/**
 * Context of a single agent invocation. Agents are stateless: everything needed for tracing and
 * recursion limits travels through this object (no ChatMemory).
 *
 * @param traceId       trace id of the HTTP request
 * @param parentEventId id of the parent pipeline event (the tool call by the calling agent),
 *                      {@code null} at the root
 * @param depth         current depth, root = 0
 * @param chain         agent ids that already ran before the current agent (cycle protection)
 * @param userMessage   user request passed to this agent
 */
public record AgentContext(
		long traceId,
		Long parentEventId,
		int depth,
		List<String> chain,
		String userMessage) {

	public AgentContext {
		chain = chain == null ? List.of() : List.copyOf(chain);
	}

	public static AgentContext root(long traceId, String userMessage) {
		return new AgentContext(traceId, null, 0, List.of(), userMessage);
	}

	/**
	 * Context for a sub-agent: depth+1, {@code agentId} appended to the chain, and the message is
	 * the request the parent agent forwards verbatim to the sub-agent.
	 */
	public AgentContext child(String agentId, Long toolEventId, String message) {
		List<String> next = new ArrayList<>(chain);
		next.add(agentId);
		return new AgentContext(traceId, toolEventId, depth + 1, next,
				message == null || message.isBlank() ? userMessage : message);
	}

	public boolean inChain(String agentId) {
		return chain.contains(agentId);
	}
}
