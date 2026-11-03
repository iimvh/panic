package com.meetingroom.core.agent.skill;

import com.meetingroom.core.agent.spi.PromptRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads system prompts from the classpath, e.g. {@code agents/booking/system.md}.
 * <p>
 * Not cached: classpath files are immutable after the build so re-reading is cheap; moving to
 * a file/DB source later only requires replacing this implementation.
 */
@Component
public class ClasspathPromptRepository implements PromptRepository {

	private static final Logger log = LoggerFactory.getLogger(ClasspathPromptRepository.class);

	private final Map<String, Optional<String>> loaded = new ConcurrentHashMap<>();

	@Override
	public Optional<String> get(String location) {
		if (location == null || location.isBlank()) {
			return Optional.empty();
		}
		return loaded.computeIfAbsent(location, this::readOne);
	}

	private Optional<String> readOne(String location) {
		ClassPathResource resource = new ClassPathResource(location);
		if (!resource.exists()) {
			return Optional.empty();
		}
		try (InputStream input = resource.getInputStream()) {
			return Optional.of(new String(input.readAllBytes(), StandardCharsets.UTF_8).trim());
		} catch (IOException ex) {
			log.warn("Cannot read prompt '{}': {}", location, ex.getMessage());
			return Optional.empty();
		}
	}
}
