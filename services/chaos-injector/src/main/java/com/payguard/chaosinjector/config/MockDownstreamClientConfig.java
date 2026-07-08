package com.payguard.chaosinjector.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * {@code chaos-injector}'s own outbound client to {@code mock-downstream} — same 1s connect / 3s
 * read timeout shape as worker's client to {@code chaos-injector} (PHASE_7_DESIGN.md Contracts), so
 * a genuinely slow/unhealthy {@code mock-downstream} surfaces the same way a DROP/LATENCY chaos
 * mode would.
 */
@Configuration
public class MockDownstreamClientConfig {

  @Bean
  public RestClient mockDownstreamRestClient(
      @Value("${payguard.mock-downstream.url}") String mockDownstreamUrl) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(1));
    requestFactory.setReadTimeout(Duration.ofSeconds(3));
    return RestClient.builder().baseUrl(mockDownstreamUrl).requestFactory(requestFactory).build();
  }
}
