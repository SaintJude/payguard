package com.payguard.worker.downstream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DownstreamProcessorTest {

  private static final String BASE_URL = "http://chaos-injector:8080";

  private RestClient.Builder builderWithMockServer(MockRestServiceServer[] serverOut) {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    serverOut[0] = MockRestServiceServer.bindTo(builder).build();
    return builder;
  }

  @Test
  void successfulCallDoesNotThrow() {
    MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
    RestClient.Builder builder = builderWithMockServer(serverHolder);
    UUID paymentId = UUID.randomUUID();

    serverHolder[0]
        .expect(requestTo(BASE_URL + "/v1/process"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
        .andExpect(jsonPath("$.amount").value(10.0))
        .andRespond(
            withSuccess(
                "{\"paymentId\": \"%s\", \"status\": \"PROCESSED\"}".formatted(paymentId),
                MediaType.APPLICATION_JSON));

    DownstreamProcessor processor = new DownstreamProcessor(builder.build());

    assertThatCode(() -> processor.process(paymentId, new BigDecimal("10.0")))
        .doesNotThrowAnyException();
    serverHolder[0].verify();
  }

  @Test
  void serverErrorResponseThrowsTransientDownstreamException() {
    MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
    RestClient.Builder builder = builderWithMockServer(serverHolder);
    UUID paymentId = UUID.randomUUID();

    serverHolder[0]
        .expect(requestTo(BASE_URL + "/v1/process"))
        .andRespond(withServiceUnavailable());

    DownstreamProcessor processor = new DownstreamProcessor(builder.build());

    assertThatThrownBy(() -> processor.process(paymentId, BigDecimal.TEN))
        .isInstanceOf(TransientDownstreamException.class);
  }

  @Test
  void genericServerErrorAlsoThrowsTransientDownstreamException() {
    MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
    RestClient.Builder builder = builderWithMockServer(serverHolder);
    UUID paymentId = UUID.randomUUID();

    serverHolder[0].expect(requestTo(BASE_URL + "/v1/process")).andRespond(withServerError());

    DownstreamProcessor processor = new DownstreamProcessor(builder.build());

    assertThatThrownBy(() -> processor.process(paymentId, BigDecimal.TEN))
        .isInstanceOf(TransientDownstreamException.class);
  }

  @Test
  void badRequestResponseThrowsPermanentDownstreamException() {
    MockRestServiceServer[] serverHolder = new MockRestServiceServer[1];
    RestClient.Builder builder = builderWithMockServer(serverHolder);
    UUID paymentId = UUID.randomUUID();

    serverHolder[0].expect(requestTo(BASE_URL + "/v1/process")).andRespond(withBadRequest());

    DownstreamProcessor processor = new DownstreamProcessor(builder.build());

    assertThatThrownBy(() -> processor.process(paymentId, BigDecimal.TEN))
        .isInstanceOf(PermanentDownstreamException.class);
  }

  @Test
  void connectionFailureThrowsTransientDownstreamException() {
    // Nothing is listening on this port -- simulates chaos-injector's DROP mode / a genuine
    // network failure, both of which must be retried like any other transient failure.
    RestClient unreachableClient = RestClient.builder().baseUrl("http://localhost:1").build();
    DownstreamProcessor processor = new DownstreamProcessor(unreachableClient);
    UUID paymentId = UUID.randomUUID();

    assertThatThrownBy(() -> processor.process(paymentId, BigDecimal.TEN))
        .isInstanceOf(TransientDownstreamException.class);
  }
}
