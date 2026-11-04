package com.panic.agents;

/**
 * Constants for the bundled agents of this module.
 * <p>
 * {@code panic-agents-core} holds agents that make up the base product (currently only the
 * {@code coordinator}). Domain agents belong in their own module, never in {@code panic-core}.
 */
public final class PanicAgentConfig {

	public static final String COORDINATOR = "coordinator";

	private PanicAgentConfig() {
	}
}
