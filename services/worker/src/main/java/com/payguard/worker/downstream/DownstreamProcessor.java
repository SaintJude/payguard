package com.payguard.worker.downstream;

import com.payguard.worker.downstream.dto.ProcessRequest;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Makes the real HTTP call to {@code chaos-injector} (never {@code mock-downstream} directly — see
 * PHASE_7_DESIGN.md Decision 1/2). Replaces Phase 1's hardcoded per-payment in-process stub
 * entirely; the retry-worthiness of a failure now comes from a real HTTP outcome instead of a
 * synthetic first-call failure.
 */
@Component
public class DownstreamProcessor {

  private final RestClient chaosInjectorRestClient;

  public DownstreamProcessor(RestClient chaosInjectorRestClient) {
    this.chaosInjectorRestClient = chaosInjectorRestClient;
  }

  public void process(UUID paymentId, BigDecimal amount) {
    try {
      chaosInjectorRestClient
          .post()
          .uri("/v1/process")
          .body(new ProcessRequest(paymentId, amount))
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException ex) {
      // chaos-injector's ERROR_4XX mode (Wave 3) returns 400 without ever calling
      // mock-downstream -- not worth retrying, see PermanentDownstreamException's javadoc.
      throw new PermanentDownstreamException(
          "chaos-injector rejected payment " + paymentId + " with " + ex.getStatusCode(), ex);
    } catch (HttpServerErrorException ex) {
      // chaos-injector's ERROR_5XX mode (Wave 3) short-circuits with a 503.
      throw new TransientDownstreamException(
          "chaos-injector returned a server error for payment " + paymentId, ex);
    } catch (RestClientException ex) {
      // Connection refused, or a read timeout -- chaos-injector's DROP mode, or LATENCY set past
      // worker's 3s read timeout (Wave 3).
      throw new TransientDownstreamException(
          "chaos-injector call failed for payment " + paymentId, ex);
    }
  }
}
