package com.insurer.claims.repository;

import com.insurer.claims.entity.ProcessedPaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedPaymentEventRepository extends JpaRepository<ProcessedPaymentEvent, UUID> {

    boolean existsByEventId(String eventId);
}
