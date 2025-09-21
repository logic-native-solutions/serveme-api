package com.logicnativesolution.servemeapi.entities;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Data
@Table(name = "payments_details")
public class PaymentsDetails {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "card_number")
    private String cardNumber;

    @Column(name = "account_number")
    private String accountNumber;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "card_holder_name")
    private String cardHolderName;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "cvv")
    private String cvv;

}
