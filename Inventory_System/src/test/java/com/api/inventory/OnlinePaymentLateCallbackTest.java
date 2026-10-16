package com.api.inventory;

import com.api.inventory.entity.Payment;
import com.api.inventory.entity.PaymentIntent;
import com.api.inventory.repository.BankPaymentRepository;
import com.api.inventory.repository.OrderRepository;
import com.api.inventory.repository.PaymentIntentRepository;
import com.api.inventory.repository.PaymentRepository;
import com.api.inventory.service.AuditService;
import com.api.inventory.service.NotificationService;
import com.api.inventory.service.OnlinePaymentService;
import com.api.inventory.service.OrderService;
import com.api.inventory.service.payments.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OnlinePaymentLateCallbackTest {
    private final PaymentIntentRepository intents = mock(PaymentIntentRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final BankPaymentRepository bankPayments = mock(BankPaymentRepository.class);
    private final OrderService orderService = mock(OrderService.class);
    private final NotificationService notify = mock(NotificationService.class);
    private final AuditService audit = mock(AuditService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private OnlinePaymentService service;

    @BeforeEach
    void setup() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new OnlinePaymentService(intents, orders, payments, bankPayments,
                orderService, List.<PaymentGateway>of(), notify, audit, transactionManager);
    }

    private PaymentIntent intent(String status) {
        PaymentIntent intent = new PaymentIntent();
        intent.setReference("PI-LATE123");
        intent.setOrderId(42L);
        intent.setCustomerEmail("test@example.invalid");
        intent.setAmount(new BigDecimal("100.00"));
        intent.setProvider("SANDBOX");
        intent.setStatus(status);
        intent.setCreatedAt(Instant.now().minusSeconds(4000));
        if (!PaymentIntent.CREATED.equals(status)) {
            intent.setCompletedAt(Instant.now().minusSeconds(1800));
        }
        when(intents.findByReferenceForUpdate("PI-LATE123")).thenReturn(Optional.of(intent));
        return intent;
    }

    private PaymentGateway.Result verifiedSuccess() {
        return new PaymentGateway.Result("PI-LATE123", true, "BANK-JOURNAL-123",
                new BigDecimal("100.00"), "Bank confirmed payment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "EXPIRED", "FAILED"})
    void lateSuccessRecordsPendingReceiptWithoutConfirmingOrder(String previousStatus) {
        PaymentIntent intent = intent(previousStatus);

        service.complete(verifiedSuccess());

        assertEquals(PaymentIntent.PAID, intent.getStatus());
        assertTrue(intent.getMessage().contains("STAFF RECONCILIATION REQUIRED"));
        assertEquals("BANK-JOURNAL-123", intent.getProviderReference());

        var receipt = org.mockito.ArgumentCaptor.forClass(Payment.class);
        verify(payments).save(receipt.capture());
        assertEquals(42L, receipt.getValue().getOrderId());
        assertEquals(new BigDecimal("100.00"), receipt.getValue().getAmount());
        assertEquals("pending", receipt.getValue().getStatus());
        assertEquals("online", receipt.getValue().getPaymentMethod());
        assertTrue(receipt.getValue().getJournalNumber().contains("BANK-JOURNAL-123"));

        verify(notify).withPermission(eq("payments.verify"), any(NotificationService.Note.class), eq(true));
        verify(audit).record(eq("ONLINE_PAYMENT_LATE_SUCCESS"), any(), any());
        verifyNoInteractions(orderService);
    }

    @Test
    void existingReceiptIsNeverOverwrittenByLateSuccess() {
        intent(PaymentIntent.CANCELLED);
        Payment existing = new Payment();
        existing.setOrderId(42L);
        existing.setStatus("confirmed");
        existing.setAmount(new BigDecimal("100.00"));
        when(payments.findByOrderId(42L)).thenReturn(Optional.of(existing));

        service.complete(verifiedSuccess());

        verify(payments, never()).save(any(Payment.class));
        assertEquals("confirmed", existing.getStatus());
        verify(notify).withPermission(eq("payments.verify"), any(NotificationService.Note.class), eq(true));
        verifyNoInteractions(orderService);
    }

    @Test
    void duplicateSuccessForPaidAttemptDoesNotCreateAnotherReceipt() {
        PaymentIntent intent = intent(PaymentIntent.PAID);

        service.complete(verifiedSuccess());

        assertEquals(PaymentIntent.PAID, intent.getStatus());
        verifyNoInteractions(payments, notify, audit, orderService);
    }
}
