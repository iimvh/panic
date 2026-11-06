package com.panic.agents.digest.web;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Renders the public digest pages through Thymeleaf and returns the HTML as a string, so the
 * controller can attach cache headers and status codes to a precisely rendered body.
 */
@Component
public class TemplateRenderer {

	private final TemplateEngine engine;

	public TemplateRenderer(TemplateEngine engine) {
		this.engine = engine;
	}

	public String render(String template, Map<String, Object> variables) {
		Context context = new Context();
		variables.forEach(context::setVariable);
		context.setVariable("templates", "digest");
		return engine.process(template, context);
	}
}
