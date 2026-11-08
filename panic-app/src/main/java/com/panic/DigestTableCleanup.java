package com.panic;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-off cleanup of the retired digest persistence. The digest agent now derives its facts live
 * from web search and stores nothing, so the tables it used to own are dropped on startup. The
 * statements are {@code IF EXISTS}, so this is safe on a fresh database and on every later boot.
 * <p>
 * Runs after the context is ready (an {@link ApplicationRunner}) so the DataSource is initialized;
 * failures are logged, never fatal — a locked/absent table must not stop the app.
 */
@Component
public class DigestTableCleanup implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DigestTableCleanup.class);
	private static final String[] RETIRED_TABLES = { "digest_news_items", "digests", "digest_feed_sources" };

	private final JdbcTemplate jdbc;

	public DigestTableCleanup(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	@Override
	public void run(ApplicationArguments args) {
		for (String table : RETIRED_TABLES) {
			try {
				jdbc.execute("DROP TABLE IF EXISTS " + table);
			} catch (RuntimeException ex) {
				log.warn("Could not drop retired digest table '{}': {}", table, ex.getMessage());
			}
		}
	}
}
