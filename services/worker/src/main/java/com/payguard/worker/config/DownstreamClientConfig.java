package com.payguard.worker.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Worker's outbound client to {@code chaos-injector} (PHASE_7_DESIGN.md Contracts: 1s connect / 3s
 * read timeout — the read timeout is what turns chaos-injector's DROP mode, or LATENCY set past
 * this threshold, into a client-side timeout {@link
 * com.payguard.worker.downstream.DownstreamProcessor} maps to {@link
 * com.payguard.worker.downstream.TransientDownstreamException}).
 */
@Configuration
public class DownstreamClientConfig {

  @Bean
  public RestClient chaosInjectorRestClient(
      @Value("${payguard.chaos-injector.url}") String chaosInjectorUrl) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(1));
    requestFactory.setReadTimeout(Duration.ofSeconds(3));
    return RestClient.builder().baseUrl(chaosInjectorUrl).requestFactory(requestFactory).build();
  }
}
