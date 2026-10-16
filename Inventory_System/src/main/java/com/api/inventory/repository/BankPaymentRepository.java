package com.api.inventory.repository;

import com.api.inventory.entity.BankPayment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BankPaymentRepository extends JpaRepository<BankPayment, Long> {

    Optional<BankPayment> findByIntentReference(String intentReference);

    List<BankPayment> findByStatusOrderByCreatedAtAsc(String status);

    /** Staff need both unknown results and requests interrupted before saving a result. */
    List<BankPayment> findByStatusInOrderByCreatedAtAsc(List<String> statuses);

    /** Exclude any bank debit in progress, uncertain, or already collected. */
    boolean existsByIntentReferenceAndStatusIn(String intentReference, List<String> statuses);

    /** The bank payment that paid this order (for the receipt). */
    Optional<BankPayment> findFirstByOrderIdAndStatusOrderByIdDesc(Long orderId, String status);

    boolean existsByIntentReferenceAndStatus(String intentReference, String status);

    /** Locked: pressing Pay twice at the same moment is handled once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BankPayment b where b.intentReference = :ref")
    Optional<BankPayment> findByIntentReferenceForUpdate(@Param("ref") String reference);

    /** The attempt was cancelled or replaced: an unfinished bank payment for it is closed too. */
    @Modifying
    @Query("update BankPayment b set b.status = :status, b.completedAt = :now"
            + " where b.intentReference = :ref and b.status in ('STARTED', 'CODE_SENT')")
    int closeOpen(@Param("ref") String reference, @Param("status") String status, @Param("now") Instant now);

    /** Bank payments whose attempt expired. */
    @Modifying
    @Query("update BankPayment b set b.status = 'EXPIRED', b.completedAt = :now where b.status in ('STARTED', 'CODE_SENT')"
            + " and b.intentReference in (select p.reference from PaymentIntent p where p.status = 'EXPIRED')")
    int expireClosedAttempts(@Param("now") Instant now);
}
