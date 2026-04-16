package com.pipeline.intelligence_bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@SpringBootApplication
public class IntelligenceBotApplication {

	private static final Logger logger = LoggerFactory.getLogger(IntelligenceBotApplication.class);

	public static void main(String[] args) {
		loadLocalEnvironmentFile();
		SpringApplication.run(IntelligenceBotApplication.class, args);
	}

	private static void loadLocalEnvironmentFile() {
		List<Path> candidates = List.of(
				Path.of(".env"),
				Path.of("intelligence-bot/.env"),
				Path.of("backend/intelligence-bot/.env")
		);
		Path envFile = null;

		for (Path candidate : candidates) {
			if (Files.isRegularFile(candidate)) {
				envFile = candidate;
				break;
			}
		}

		if (envFile == null) {
			return;
		}

		try {
			List<String> lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);

			for (String rawLine : lines) {
				String line = rawLine == null ? "" : rawLine.trim();

				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}

				if (line.startsWith("export ")) {
					line = line.substring("export ".length()).trim();
				}

				int equalsIndex = line.indexOf('=');

				if (equalsIndex <= 0) {
					continue;
				}

				String key = line.substring(0, equalsIndex).trim();
				String value = line.substring(equalsIndex + 1).trim();

				if (key.isEmpty()) {
					continue;
				}

				if ((value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2)
						|| (value.startsWith("'") && value.endsWith("'") && value.length() >= 2)) {
					value = value.substring(1, value.length() - 1);
				}

				if (System.getenv(key) == null && System.getProperty(key) == null) {
					System.setProperty(key, value);
					logger.debug("Loaded {} from local .env file", key);
				}
			}
		} catch (IOException exception) {
			logger.warn("Unable to read local .env file: {}", exception.getMessage());
		}
	}

}
