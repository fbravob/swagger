package org.snomed.snowstorm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.snowstorm.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.aws.autoconfigure.context.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.rest.ElasticsearchRestClient;
import pl.allegro.tech.embeddedelasticsearch.EmbeddedElastic;
import pl.allegro.tech.embeddedelasticsearch.EmbeddedElasticsearchStartupException;
import pl.allegro.tech.embeddedelasticsearch.PopularProperties;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

@PropertySource("application.properties")
@PropertySource("application-test.properties")
@TestConfiguration
@SpringBootApplication(
		exclude = {ContextCredentialsAutoConfiguration.class,
				ContextInstanceDataAutoConfiguration.class,
				ContextRegionProviderAutoConfiguration.class,
				ContextResourceLoaderAutoConfiguration.class,
				ContextStackAutoConfiguration.class,
				ElasticsearchAutoConfiguration.class,
				ElasticsearchDataAutoConfiguration.class})
public class TestConfig extends Config {

	private static final String ELASTIC_SEARCH_VERSION = "6.4.2";
	public static final List<String> DEFAULT_LANGUAGE_CODES = Collections.singletonList("en");

	@Autowired
	private ElasticsearchOperations elasticsearchTemplate;

	private static EmbeddedElastic testElasticsearchSingleton;
	private static final int PORT = 9931;

	@Bean
	public ElasticsearchRestClient elasticsearchClient() {
		// Share the Elasticsearch instance between test contexts
		if (testElasticsearchSingleton == null) {
			// Create and start a clean standalone Elasticsearch test instance
			String clusterName = "integration-test-cluster";
			try {
				testElasticsearchSingleton = EmbeddedElastic.builder()
						.withElasticVersion(ELASTIC_SEARCH_VERSION)
						.withStartTimeout(30, TimeUnit.SECONDS)
						.withSetting(PopularProperties.CLUSTER_NAME, clusterName)
						.withSetting(PopularProperties.HTTP_PORT, PORT)
						.build();

				testElasticsearchSingleton
						.start()
						.deleteIndices();
			} catch (InterruptedException | IOException e) {
				throw new RuntimeException("Failed to start standalone Elasticsearch instance.", e);
			}
		}

		// Create client to to standalone instance
		return new ElasticsearchRestClient(new HashMap<>(), "http://localhost:" + PORT);
	}

	@PreDestroy
	public void shutdown() {
		synchronized (TestConfig.class) {
			Logger logger = LoggerFactory.getLogger(getClass());
			if (testElasticsearchSingleton != null) {
				try {
					testElasticsearchSingleton.stop();
				} catch (Exception e) {
					logger.info("The test Elasticsearch instance threw an exception during shutdown, probably due to multiple test contexts. This can be ignored.");
					logger.debug("The test Elasticsearch instance threw an exception during shutdown.", e);
				}
			}
			testElasticsearchSingleton = null;
		}
	}

}
