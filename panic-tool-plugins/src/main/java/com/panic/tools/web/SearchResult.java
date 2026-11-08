package com.panic.tools.web;

/**
 * One search hit. {@code url} is the resolved destination (unwrapped from the engine's redirect);
 * {@code snippet} may be empty when the result page carries no abstract. {@code publishedAt} is an
 * ISO-8601 timestamp when the backend knows it (news feeds do, plain web pages usually do not) so the
 * model can tell fresh news from evergreen pages.
 */
public record SearchResult(String title, String url, String snippet, String publishedAt) {

	public SearchResult(String title, String url, String snippet) {
		this(title, url, snippet, null);
	}
}
