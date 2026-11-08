package com.panic.tools.web;

/**
 * Seam for a web-search provider. The {@code web-search} tool depends on this interface, not on a
 * concrete engine, mirroring the harness "capability seam" idea: swapping DuckDuckGo for a
 * self-hosted SearXNG (or an API) is a provider change, not a tool change.
 */
public interface SearchBackend {

	/** Human-facing name of the backend, e.g. {@code duckduckgo-html}. */
	String name();

	/** Runs one query and returns the best matches. Never returns null. */
	java.util.List<SearchResult> search(String query, int limit);
}
