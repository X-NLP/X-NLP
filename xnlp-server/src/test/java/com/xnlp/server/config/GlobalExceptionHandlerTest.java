package com.xnlp.server.config;

import com.xnlp.server.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final ObjectProvider<?> noTracer = mock(ObjectProvider.class);

    @Test
    void resourceNotFound_usesStableContractAndPropagatesRequestId() {
        when(noTracer.getIfAvailable()).thenReturn(null);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(noTracer());
        MockHttpServletRequest request = requestWithId("req-123");

        ResponseEntity<ApiErrorResponse> response = handler.handle(
                new NoSuchElementException("Dataset not found: ds-1"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("resource_not_found");
        assertThat(response.getBody().message()).isEqualTo("Dataset not found: ds-1");
        assertThat(response.getBody().requestId()).isEqualTo("req-123");
        assertThat(response.getBody().traceId()).isNull();
    }

    @Test
    void providerStateFailure_hasActionableStableErrorCode() {
        when(noTracer.getIfAvailable()).thenReturn(null);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(noTracer());

        ResponseEntity<ApiErrorResponse> response = handler.handle(
                new IllegalStateException("No Spring AI provider is configured"), requestWithId(null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("provider_unavailable");
        assertThat(response.getBody().requestId()).isNotBlank();
    }

    @Test
    void unexpectedFailure_doesNotExposeInternalExceptionMessage() {
        when(noTracer.getIfAvailable()).thenReturn(null);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(noTracer());

        ResponseEntity<ApiErrorResponse> response = handler.handleUnexpected(
                new RuntimeException("jdbc password=secret"), requestWithId("req-safe"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("internal_error");
        assertThat(response.getBody().message()).doesNotContain("secret");
        assertThat(response.getBody().requestId()).isEqualTo("req-safe");
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<io.micrometer.tracing.Tracer> noTracer() {
        return (ObjectProvider<io.micrometer.tracing.Tracer>) noTracer;
    }

    private static MockHttpServletRequest requestWithId(String id) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (id != null) {
            request.addHeader("X-Request-ID", id);
        }
        return request;
    }
}
