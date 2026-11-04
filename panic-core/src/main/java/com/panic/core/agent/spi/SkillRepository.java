package com.panic.core.agent.spi;

import java.util.List;
import java.util.Optional;

public interface SkillRepository {

	List<Skill> list();

	Optional<Skill> get(String id);

	/** Skills declared by an agent, skipping ids that do not exist. */
	default List<Skill> get(List<String> ids) {
		return ids.stream().map(this::get).flatMap(Optional::stream).toList();
	}
}
