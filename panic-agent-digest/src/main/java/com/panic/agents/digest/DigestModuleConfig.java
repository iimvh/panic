package com.panic.agents.digest;

import com.panic.agents.digest.digest.DefaultDigestService;
import com.panic.agents.digest.digest.DigestService;
import com.panic.agents.digest.digest.DigestSummarizer;
import com.panic.agents.digest.digest.VietnameseDigestWriter;
import com.panic.agents.digest.feed.BuiltInFeedSources;
import com.panic.agents.digest.feed.FeedReader;
import com.panic.agents.digest.feed.FeedSourceRepository;
import com.panic.agents.digest.feed.FeedSourceSeed;
import com.panic.agents.digest.feed.NewsItemRepository;
import com.panic.agents.digest.digest.DigestRepository;
import com.panic.agents.digest.feed.RomeFeedReader;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the digest feature's infrastructure: the domain service, the feed reader, the model-backed
 * writer, and scheduling. Everything is guarded by {@code app.agents.digest.enabled} (the agent
 * plugin convention) so the feature can be turned off without removing code.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(DigestProperties.class)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
		name = "app.agents.digest.enabled", havingValue = "true", matchIfMissing = true)
public class DigestModuleConfig {

	@Bean
	public Clock digestClock() {
		return Clock.systemUTC();
	}

	@Bean
	public HttpClient digestHttpClient() {
		return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	@Bean
	public FeedSourceSeed digestFeedSourceSeed() {
		return BuiltInFeedSources.INSTANCE;
	}

	@Bean
	public FeedReader feedReader(HttpClient digestHttpClient, DigestProperties properties) {
		return new RomeFeedReader(digestHttpClient, Duration.ofMillis(properties.getFeedTimeoutMs()));
	}

	@Bean
	public DigestSummarizer digestSummarizer(ChatClient.Builder chatClientBuilder,
			@Value("${spring.ai.openai.api-key:}") String apiKey) {
		return new VietnameseDigestWriter(chatClientBuilder, apiKey);
	}

	@Bean
	public DigestService digestService(FeedSourceRepository sources, NewsItemRepository items,
			DigestRepository digests, FeedSourceSeed seed, FeedReader reader, DigestSummarizer summarizer,
			Clock digestClock, DigestProperties properties) {
		return new DefaultDigestService(sources, items, digests, seed, reader, summarizer, digestClock,
				properties.zoneId(), properties.getMaxItems());
	}
}
