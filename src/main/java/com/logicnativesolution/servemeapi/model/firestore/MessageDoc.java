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
public class MessageDoc {
    private String senderId;
    private String text;
    private Instant sentAt;
    private String type; // text | image
}
