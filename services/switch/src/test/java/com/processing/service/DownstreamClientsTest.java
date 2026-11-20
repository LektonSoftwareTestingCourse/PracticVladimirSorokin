package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.config.CircuitBreakerFactory;
import com.processing.config.RabbitMQConfig;
import com.processing.config.RetryFactory;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DownstreamClientsTest {
    private MockRestServiceServer server;
    private AuthorizationClient authorization;
    private MerchantAcquirerClient merchant;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient rest = builder.build();
        breaker = CircuitBreakerFactory.authorizationCircuitBreaker(SwitchData.properties());
        authorization = new AuthorizationClient(SwitchData.properties(), rest,
                RetryFactory.authorizationRetry(SwitchData.properties()), breaker);
        merchant = new MerchantAcquirerClient(SwitchData.properties(), rest);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReadAuthorizationBodyEvenForBusiness403(String status) {
        // Arrange
        server.expect(requestTo("http://auth/api/internal/authorize")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"" + status + "\",\"responseCode\":\"05\"}"));

        // Act
        AuthorizationResponse result = authorization.authorize(SwitchData.request());

        // Assert
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.responseCode()).isEqualTo("05");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"status\":\"OTHER\"}"})
    void shouldRejectMissingOrUnexpectedAuthorizationBody(String body) {
        // Arrange
        server.expect(requestTo("http://auth/api/internal/authorize"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        // Act / Assert
        assertThatThrownBy(() -> authorization.authorize(SwitchData.request()))
                .isInstanceOf(AuthorizationException.class).hasMessageContaining("Invalid response");
    }

    @Test
    void shouldRejectCallWhenCircuitIsOpen() {
        // Arrange
        breaker.transitionToOpenState();

        // Act / Assert
        assertThatThrownBy(() -> authorization.authorize(SwitchData.request()))
                .isInstanceOf(AuthorizationException.class).hasMessageContaining("Circuit breaker open");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReadBothRollbackOutcomes(String status) {
        // Arrange
        server.expect(requestTo("http://auth/api/internal/rollback"))
                .andRespond(withSuccess("{\"status\":\"" + status + "\",\"rrn\":\"012345678901\"}",
                        MediaType.APPLICATION_JSON));

        // Act
        RollbackResponse response = authorization.rollback(SwitchData.request(), "012345678901");

        // Assert
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.rrn()).isEqualTo("012345678901");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"status\":\"OTHER\"}"})
    void shouldReturnNullForInvalidRollbackBody(String body) {
        // Arrange
        server.expect(requestTo("http://auth/api/internal/rollback"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        // Act
        RollbackResponse response = authorization.rollback(SwitchData.request(), "012345678901");

        // Assert
        assertThat(response).isNull();
    }

    @ParameterizedTest
    @CsvSource({"200,ok", "503,down"})
    void shouldReportAuthorizationAvailability(int status, String expected) {
        // Arrange
        server.expect(requestTo("http://auth/health")).andRespond(withStatus(HttpStatus.valueOf(status)));

        // Act
        String result = authorization.checkHealth();

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void shouldReturnAcquiringFee() {
        // Arrange
        server.expect(requestTo("http://merchant/api/simulator/merchant/fee"))
                .andRespond(withSuccess("{\"acquirerFee\":2.50}", MediaType.APPLICATION_JSON));

        // Act
        BigDecimal fee = merchant.fetchAcquiringFee(SwitchData.request().transmissionDateTime(),
                "000001", SwitchData.PAN, "TERM0001", new BigDecimal("100"));

        // Assert
        assertThat(fee).isEqualByComparingTo("2.50");
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 503})
    void shouldKeepFeeOptionalWhenMerchantHasNoResponse(int status) {
        // Arrange
        server.expect(requestTo("http://merchant/api/simulator/merchant/fee"))
                .andRespond(withStatus(HttpStatus.valueOf(status)));

        // Act
        BigDecimal fee = merchant.fetchAcquiringFee(SwitchData.request().transmissionDateTime(),
                "000001", SwitchData.PAN, "TERM0001", new BigDecimal("100"));

        // Assert
        assertThat(fee).isNull();
    }

    @Test
    void shouldPublishTransactionToConfiguredExchange() {
        // Arrange
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        TransactionRequest transaction = mock(TransactionRequest.class);

        // Act
        boolean sent = new LoggerClient(rabbit).log(transaction);

        // Assert
        assertThat(sent).isTrue();
        verify(rabbit).convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, transaction);
    }

    @Test
    void shouldReturnFalseWhenRabbitSendThrows() {
        // Arrange
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        TransactionRequest transaction = mock(TransactionRequest.class);
        doThrow(new AmqpException("offline")).when(rabbit)
                .convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, transaction);

        // Act
        boolean sent = new LoggerClient(rabbit).log(transaction);

        // Assert
        assertThat(sent).isFalse();
    }
}
