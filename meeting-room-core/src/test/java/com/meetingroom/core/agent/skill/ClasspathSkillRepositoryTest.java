package com.meetingroom.core.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.meetingroom.core.agent.spi.Skill;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClasspathSkillRepositoryTest {

	@Test
	void parsesFrontmatterAndBody() {
		Skill skill = ClasspathSkillRepository.parse("""
				---
				name: booking-policy
				description: Booking policy
				mcpServers: [room-catalog, calendar]
				---
				# Content
				Call load_skill when needed.
				""", "booking-policy.md");

		assertThat(skill).isNotNull();
		assertThat(skill.id()).isEqualTo("booking-policy");
		assertThat(skill.name()).isEqualTo("booking-policy");
		assertThat(skill.description()).isEqualTo("Booking policy");
		assertThat(skill.requiredMcpServers()).containsExactly("room-catalog", "calendar");
		assertThat(skill.content()).startsWith("# Content").contains("load_skill");
	}

	@Test
	void emptyServerListIsAllowed() {
		Skill skill = ClasspathSkillRepository.parse("""
				---
				name: x
				description: d
				mcpServers: []
				---
				body
				""", "x.md");

		assertThat(skill.requiredMcpServers()).isEmpty();
	}

	@Test
	void missingNameIsRejected() {
		assertThat(ClasspathSkillRepository.parse("""
				---
				description: missing name
				---
				body
				""", "bad.md")).isNull();
	}

	@Test
	void missingFrontmatterIsRejected() {
		assertThat(ClasspathSkillRepository.parse("# markdown only", "plain.md")).isNull();
	}

	@Test
	void unclosedFrontmatterIsRejected() {
		assertThat(ClasspathSkillRepository.parse("""
				---
				name: x
				body unclosed
				""", "unclosed.md")).isNull();
	}

	@Test
	void loadsSkillsBundledPerAgentDirectory() {
		ClasspathSkillRepository repository = new ClasspathSkillRepository();

		assertThat(repository.get("booking-policy")).isPresent();
		assertThat(repository.get(List.of("booking-policy", "missing")))
				.extracting(Skill::id).containsExactly("booking-policy");
	}
}
