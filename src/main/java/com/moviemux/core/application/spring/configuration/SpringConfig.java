package com.moviemux.core.application.spring.configuration;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.SessionLocaleResolver;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;

@Configuration
@EnableAsync
public class SpringConfig {

	@Bean
	@Primary
	ObjectMapper objectMapper() {
		return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
				.setSerializationInclusion(Include.NON_NULL)
				.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
				.registerModule(new JavaTimeModule())
				.setDateFormat(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"))
				.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
				.enable(SerializationFeature.WRITE_DATES_WITH_ZONE_ID)
				.setTimeZone(TimeZone.getTimeZone("America/Sao_Paulo"));
	}

	@Bean
	@Primary
	LocaleResolver localeResolver() {
		SessionLocaleResolver slr = new SessionLocaleResolver();
		slr.setDefaultLocale(Locale.US);
		slr.setLocaleAttributeName("session.current.locale");
		slr.setTimeZoneAttributeName("session.current.timezone");
		return slr;
	}

	@Bean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
	@Primary
	AsyncTaskExecutor asyncTaskExecutor() {
		return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
	}

	@Bean
	@Primary
	TomcatProtocolHandlerCustomizer<?> protocolHandlerVirtualThreadExecutorCustomizer() {
		return protocolHandler -> {
			protocolHandler.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		};
	}
}
