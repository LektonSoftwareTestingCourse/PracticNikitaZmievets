package com.processing.controller;

import com.processing.config.SwitchProperties;
import com.processing.dto.HealthResponse;
import com.processing.service.AuthorizationClient;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    private AuthorizationClient authorizationClient;

    @ParameterizedTest(name = "authorization={0}")
    @ValueSource(strings = {"ok", "down"})
    void shouldReportSwitchOkAndAuthorizationDependencyStatus(String authorizationStatus) {
        // Arrange
        SwitchProperties properties = new SwitchProperties(
                "1.0.0", Map.of(), null, null, null, null, null, null);
        HealthController controller = new HealthController(properties, authorizationClient);
        when(authorizationClient.checkHealth()).thenReturn(authorizationStatus);

        // Act
        ResponseEntity<HealthResponse> response = controller.health();

        // Assert
        HealthResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo("ok");
        assertThat(body.service()).isEqualTo("switch");
        assertThat(body.version()).isEqualTo("1.0.0");
        assertThat(body.dependencies()).containsEntry("authorization", authorizationStatus);
    }
}
