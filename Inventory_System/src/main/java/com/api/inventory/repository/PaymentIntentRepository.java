package com.api.inventory.repository;

import com.api.inventory.entity.PaymentIntent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {

    Optional<PaymentIntent> findByReference(String reference);

    /** Locked: a gateway message that arrives twice at the same moment is handled once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentIntent p where p.reference = :reference")
    Optional<PaymentIntent> findByReferenceForUpdate(@Param("reference") String reference);

    List<PaymentIntent> findByOrderIdOrderByIdDesc(Long orderId);

    @Modifying
    // Never expire an attempt whose bank debit may have happened, or whose bank confirmed PAID
    // but whose order/payment intent has not yet been finalized after a crash.
    @Query("update PaymentIntent p set p.status = 'EXPIRED', p.completedAt = :now where p.status = 'CREATED' and p.createdAt < :before"
            + " and p.reference not in (select b.intentReference from BankPayment b"
            + " where b.status in ('DEBIT_PENDING', 'CHECK_BANK', 'PAID'))")
    int expireOlderThan(@Param("before") Instant before, @Param("now") Instant now);
}
