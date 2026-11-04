package com.panic.core.agent.skill;

import com.panic.core.agent.spi.Skill;
import com.panic.core.agent.spi.SkillRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Loads skills packaged per agent directory: {@code classpath*:agents/&lt;agentId&gt;/skills/*.md}.
 * <p>
 * The skill id remains the {@code name} from the frontmatter (flat), so {@code AgentDefinition.skillIds}
 * lines up across agents. A duplicate id between two agents → warning, first file wins.
 * <p>
 * Minimal frontmatter, no heavy YAML dependency:
 * <pre>
 * ---
 * name: booking-policy
 * description: ...
 * mcpServers: [room-catalog]
 * ---
 * content
 * </pre>
 * A file missing {@code name} / with broken frontmatter → skipped + WARN log (never kills the app).
 */
@Component
public class ClasspathSkillRepository implements SkillRepository {

	private static final Logger log = LoggerFactory.getLogger(ClasspathSkillRepository.class);
	private static final String DEFAULT_LOCATION = "classpath*:agents/*/skills/*.md";

	private final Map<String, Skill> byId;

	public ClasspathSkillRepository() {
		this(DEFAULT_LOCATION);
	}

	public ClasspathSkillRepository(String location) {
		this.byId = Map.copyOf(load(location));
		log.info("Skills loaded: {}", byId.keySet());
	}

	private Map<String, Skill> load(String location) {
		Map<String, Skill> result = new LinkedHashMap<>();
		Resource[] resources;
		try {
			resources = new PathMatchingResourcePatternResolver().getResources(location);
		} catch (IOException ex) {
			log.warn("Cannot scan skills at '{}': {}", location, ex.getMessage());
			return result;
		}
		Arrays.sort(resources, (a, b) -> String.valueOf(a.getFilename()).compareTo(String.valueOf(b.getFilename())));
		for (Resource resource : resources) {
			try (InputStream input = resource.getInputStream()) {
				String raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
				Skill skill = parse(raw, resource.getFilename());
				if (skill == null) {
					continue;
				}
				if (result.putIfAbsent(skill.id(), skill) != null) {
					log.warn("Duplicate skill id '{}' in {} — dropping the later file", skill.id(), resource.getFilename());
				}
			} catch (RuntimeException | IOException ex) {
				log.warn("Skipping skill {}: {}", resource.getFilename(), ex.getMessage());
			}
		}
		return result;
	}

	/**
	 * Parses one skill file. Returns null when the frontmatter is missing or invalid.
	 */
	public static Skill parse(String raw, String fileName) {
		if (raw == null || !raw.startsWith("---")) {
			log.warn("Skill {} has no frontmatter — skipping", fileName);
			return null;
		}
		int end = raw.indexOf("\n---", 3);
		if (end < 0) {
			log.warn("Skill {} has unclosed frontmatter — skipping", fileName);
			return null;
		}
		String frontmatter = raw.substring(3, end).trim();
		int bodyStart = raw.indexOf('\n', end + 1);
		String content = bodyStart < 0 ? "" : raw.substring(bodyStart + 1).trim();
		Map<String, String> fields = new LinkedHashMap<>();
		for (String line : frontmatter.split("\\R")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			int colon = trimmed.indexOf(':');
			if (colon <= 0) {
				log.warn("Skill {} has an invalid frontmatter line: '{}' — skipping", fileName, trimmed);
				return null;
			}
			fields.put(trimmed.substring(0, colon).trim(), trimmed.substring(colon + 1).trim());
		}
		String name = fields.get("name");
		if (name == null || name.isBlank()) {
			log.warn("Skill {} is missing 'name' — skipping", fileName);
			return null;
		}
		String description = fields.getOrDefault("description", "");
		List<String> servers = listOf(fields.get("mcpServers"));
		return new Skill(name, name, description, content, servers);
	}

	private static List<String> listOf(String value) {
		if (value == null) {
			return List.of();
		}
		String cleaned = value.trim();
		if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
			cleaned = cleaned.substring(1, cleaned.length() - 1);
		}
		if (cleaned.isBlank()) {
			return List.of();
		}
		List<String> items = new ArrayList<>();
		for (String item : cleaned.split(",")) {
			String trimmed = item.trim().replaceAll("^[\"']|[\"']$", "");
			if (!trimmed.isBlank()) {
				items.add(trimmed);
			}
		}
		return items;
	}

	@Override
	public List<Skill> list() {
		return List.copyOf(byId.values());
	}

	@Override
	public Optional<Skill> get(String id) {
		return Optional.ofNullable(id).flatMap(key -> Optional.ofNullable(byId.get(key)));
	}
}
