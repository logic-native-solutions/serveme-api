package com.logicnativesolution.servemeapi.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "payments")
public class Payment {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "method")
    private String method;

    @Column(name = "status")
    private String status;

}