package com.panic.core.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import(ConversationStoreTest.TestConfig.class)
class ConversationStoreTest {

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class TestConfig {
	}

	@Autowired
	private ConversationRepository conversations;

	@Autowired
	private ChatTurnRepository turns;

	private ConversationStore store;

	@BeforeEach
	void setUp() {
		store = new ConversationStore(conversations, turns, 3);
	}

	@Test
	void newConversationExists() {
		String id = store.create();
		assertThat(store.exists(id)).isTrue();
		assertThat(store.exists("nope")).isFalse();
		assertThat(store.exists(null)).isFalse();
	}

	@Test
	void appendAndLoadInOrder() {
		String id = store.create();
		store.append(id, ChatTurn.Role.USER, "hello");
		store.append(id, ChatTurn.Role.ASSISTANT, "hi there");

		var messages = store.loadMessages(id);
		assertThat(messages).hasSize(2);
		assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
		assertThat(messages.get(0).getText()).isEqualTo("hello");
		assertThat(messages.get(1)).isInstanceOf(AssistantMessage.class);
		assertThat(messages.get(1).getText()).isEqualTo("hi there");
	}

	@Test
	void windowKeepsOnlyMostRecentTurns() {
		String id = store.create();
		for (int i = 1; i <= 5; i++) {
			store.append(id, ChatTurn.Role.USER, "u" + i);
		}

		var messages = store.loadMessages(id);
		assertThat(messages).extracting(m -> m.getText()).containsExactly("u3", "u4", "u5");
	}

	@Test
	void appendToUnknownConversationIsIgnored() {
		store.append("missing", ChatTurn.Role.USER, "x");
		assertThat(store.loadMessages("missing")).isEmpty();
	}

	@Test
	void unknownConversationHasNoMessages() {
		assertThat(store.loadMessages("nope")).isEmpty();
	}
}
