package com.panic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Entry point.
 * <p>
 * {@code .env} lives at the repo root, but {@code mvn -pl panic-app -am spring-boot:run} runs the JVM
 * with its working directory set to {@code panic-app/}. Without help, {@code spring-dotenv} looks
 * for {@code .env} in the wrong place and the API key silently falls back to the placeholder.
 * This initializer points dotenv at the nearest ancestor directory that actually has the file.
 * Precedence: an explicit {@code springdotenv.directory} / {@code SPRINGDOTENV_DIRECTORY}, a real
 * OS environment variable, then the discovered {@code .env}.
 */
@SpringBootApplication(scanBasePackages = "com.panic")
public class PanicApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(PanicApplication.class);
		app.addInitializers(PanicApplication::pointDotenvAtProjectRoot);
		app.addInitializers(new DotenvApplicationInitializer());
		app.run(args);
	}

	/** Sets {@code springdotenv.directory} so dotenv finds the {@code .env} above the working directory. */
	static void pointDotenvAtProjectRoot(ConfigurableApplicationContext context) {
		var env = context.getEnvironment();
		if (env.getProperty("springdotenv.directory") != null
				|| env.getProperty("SPRINGDOTENV_DIRECTORY") != null
				|| System.getenv("OPENROUTER_API_KEY") != null) {
			return;
		}
		Path root = findDotenvDirectory(Path.of("").toAbsolutePath());
		if (root != null) {
			env.getPropertySources().addFirst(new MapPropertySource("panic-dotenv-dir",
					Map.of("springdotenv.directory", root.toString())));
		}
	}

	static Path findDotenvDirectory(Path start) {
		for (Path dir = start; dir != null; dir = dir.getParent()) {
			if (Files.isRegularFile(dir.resolve(".env"))) {
				return dir;
			}
		}
		return null;
	}
}
