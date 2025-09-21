package com.logicnativesolution.servemeapi.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Setter
@Getter
@Table(name = "documents")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "front_id_image")
    private byte[] frontIdImage;

    @Column(name = "back_id_image")
    private byte[] backIdImage;

    @Column(name = "face_image_holding_id")
    private byte[] faceImageHoldingId;

    @Column(name = "status")
    private boolean status;
}
