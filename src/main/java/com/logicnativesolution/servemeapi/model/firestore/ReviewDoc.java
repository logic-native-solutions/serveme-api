package com.logicnativesolution.servemeapi.model.firestore;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewDoc {
    private String jobId;
    private String clientId;
    private String providerId;
    private Integer rating; // 1..5
    private String comment;
    private Instant createdAt;
}
