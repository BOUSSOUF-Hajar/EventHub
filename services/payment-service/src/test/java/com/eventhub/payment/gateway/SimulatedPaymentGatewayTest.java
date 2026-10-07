package com.eventhub.payment.gateway;

import com.eventhub.payment.config.PaymentProperties;
import com.eventhub.payment.gateway.PaymentGateway.ChargeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PAY-4 : les deux bornes du taux d'echec sont celles qu'on utilise en demo, elles doivent etre exactes. */
class SimulatedPaymentGatewayTest {

    private static final BigDecimal AMOUNT = new BigDecimal("50.00");

    @Test
    @DisplayName("failure-rate = 0.0 : aucun paiement n'est refuse")
    void zeroFailureRateNeverDeclines() {
        PaymentGateway gateway = new SimulatedPaymentGateway(new PaymentProperties(0.0));

        assertThat(IntStream.range(0, 1_000).mapToObj(i -> gateway.charge(UUID.randomUUID(), AMOUNT)))
                .allMatch(ChargeResult::approved);
    }

    @Test
    @DisplayName("failure-rate = 1.0 : tous les paiements sont refuses, avec un motif")
    void fullFailureRateAlwaysDeclines() {
        PaymentGateway gateway = new SimulatedPaymentGateway(new PaymentProperties(1.0));

        assertThat(IntStream.range(0, 1_000).mapToObj(i -> gateway.charge(UUID.randomUUID(), AMOUNT)))
                .allSatisfy(result -> {
                    assertThat(result.approved()).isFalse();
                    assertThat(result.declineReason()).isEqualTo("CARD_DECLINED");
                });
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, Double.NaN})
    @DisplayName("un taux hors de [0, 1] est une erreur de configuration")
    void outOfRangeFailureRateIsRejected(double failureRate) {
        assertThatThrownBy(() -> new PaymentProperties(failureRate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payment.failure-rate");
    }
}
