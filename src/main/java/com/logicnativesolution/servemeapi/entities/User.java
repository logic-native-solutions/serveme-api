package com.logicnativesolution.servemeapi.entities;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "role_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer","handler"})
    private Role role;

    @OneToOne(mappedBy = "user")
    @JoinColumn(name = "documents_id")
    private Document document;

    @OneToMany(mappedBy = "user")
    private List<Address> addresses;

    @OneToMany(mappedBy = "provider")
    private List<ServiceArea> ServiceAreas;

    @OneToMany(mappedBy = "user")
    private List<PaymentsDetails> paymentsDetails;

    @Column(name = "id_number")
    private String idNumber;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "email")
    private String email;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "gender")
    private String gender;

    @Column(name = "verified")
    private boolean verified;

    @Column(name = "password")
    private String password;

}