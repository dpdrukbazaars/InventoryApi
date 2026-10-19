package com.api.inventory.repository;

import com.api.inventory.entity.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {

    List<PaymentEvent> findByIntentReferenceOrderByAtAsc(String intentReference);
    List<PaymentEvent> findByProviderReference(String providerReference);
    /** For example: how many codes this customer had sent in the last hour. */
    long countByActorAndStepAndAtAfter(String actor, String step, Instant after);
}
