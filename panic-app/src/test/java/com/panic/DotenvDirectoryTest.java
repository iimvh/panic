package com.panic;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DotenvDirectoryTest {

	@Test
	void pointsToTheNearestAncestorWithDotenv(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
		Files.createFile(root.resolve(".env"));
		Path nested = Files.createDirectories(root.resolve("a/b"));

		assertThat(PanicApplication.findDotenvDirectory(nested)).isEqualTo(root);
	}

	@Test
	void returnsNullWhenNoDotenvAbove() {
		assertThat(PanicApplication.findDotenvDirectory(Path.of(System.getProperty("java.io.tmpdir"))))
				.satisfiesAnyOf(
						dir -> assertThat(dir).isNull(),
						dir -> assertThat(dir.resolve(".env")).exists());
	}
}
