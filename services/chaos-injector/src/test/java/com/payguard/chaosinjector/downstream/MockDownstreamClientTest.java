package com.payguard.chaosinjector.downstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.payguard.chaosinjector.dto.ProcessRequest;
import com.payguard.chaosinjector.dto.ProcessResponse;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MockDownstreamClientTest {

  @Test
  void postsToProcessAndParsesTheResponse() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://mock-downstream:8080");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    UUID paymentId = UUID.randomUUID();

    server
        .expect(requestTo("http://mock-downstream:8080/v1/process"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
        .andRespond(
            withSuccess(
                "{\"paymentId\": \"%s\", \"status\": \"PROCESSED\"}".formatted(paymentId),
                MediaType.APPLICATION_JSON));

    MockDownstreamClient client = new MockDownstreamClient(builder.build());

    ProcessResponse response =
        client.process(new ProcessRequest(paymentId, new BigDecimal("10.00")));

    assertThat(response.paymentId()).isEqualTo(paymentId);
    assertThat(response.status()).isEqualTo("PROCESSED");
    server.verify();
  }
}
