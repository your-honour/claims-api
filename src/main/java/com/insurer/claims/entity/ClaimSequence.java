package com.insurer.claims.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Backs the human-readable claim reference ({@code Claim#getClaimReference()}):
 * a claims analyst can't easily write down or read out a UUID over the
 * phone, so each claim also gets a short sequential number formatted as
 * {@code CLM-000123}.
 *
 * <p>Deliberately just a bare auto-increment id, not a real business
 * entity - inserting one row and reading back its DB-assigned id is a
 * standard, portable way to get a race-free sequential number via plain
 * JPA {@code IDENTITY} generation, without relying on a native SQL
 * dialect's sequence syntax (which differs between H2 and Postgres). See
 * {@code ClaimReferenceGenerator}.
 */
@Entity
@Table(name = "claim_sequence")
@Getter
public class ClaimSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
}
