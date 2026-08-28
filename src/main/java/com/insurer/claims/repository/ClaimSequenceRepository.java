package com.insurer.claims.repository;

import com.insurer.claims.entity.ClaimSequence;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimSequenceRepository extends JpaRepository<ClaimSequence, Long> {
}
