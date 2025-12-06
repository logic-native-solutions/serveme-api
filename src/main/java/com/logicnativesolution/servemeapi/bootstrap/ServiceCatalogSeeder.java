package com.logicnativesolution.servemeapi.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicnativesolution.servemeapi.model.firestore.ServiceDoc;
import com.logicnativesolution.servemeapi.service.FirestoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceCatalogSeeder implements ApplicationRunner {

    private final FirestoreService firestoreService;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            // Minimal example service: plumber
            ServiceDoc plumber = ServiceDoc.builder()
                    .displayName("Plumber")
                    .basePrice(4900L) // $49.00
                    .addOns(List.of(
                            ServiceDoc.AddOn.builder().id("unclog_drain").label("Unclog drain").price(2900L).build(),
                            ServiceDoc.AddOn.builder().id("leak_fix").label("Leak fix").price(3500L).build()
                    ))
                    .minRadiusKm(0)
                    .maxRadiusKm(50)
                    .build();

            Map<String,Object> map = objectMapper.convertValue(plumber, Map.class);
            firestoreService.set("services", "Plumber", map);
//            log.info("[Seeder] Ensured 'services/plumber' exists in Firestore");

            ServiceDoc cleaning = ServiceDoc.builder()
                    .displayName("Cleaning")
                    .basePrice(6900L)
                    .addOns(List.of(
                            ServiceDoc.AddOn.builder().id("deep_clean").label("Deep clean").price(2500L).build(),
                            ServiceDoc.AddOn.builder().id("fridge").label("Clean fridge").price(1200L).build(),
                            ServiceDoc.AddOn.builder().id("house_cleaning").label("House cleaning").price(23900L).build()
                    ))
                    .minRadiusKm(0).maxRadiusKm(50).build();
            firestoreService.set("services", "Cleaning", objectMapper.convertValue(cleaning, Map.class));
            ServiceDoc electrician = ServiceDoc.builder()
                    .displayName("Electrical")
                    .basePrice(1900L)
                    .addOns(List.of(
                            ServiceDoc.AddOn.builder().id("plug_repair").label("Plug repair").price(1000L).build()
                    ))
                    .minRadiusKm(0).maxRadiusKm(50).build();
            firestoreService.set("services", "Electrical", objectMapper.convertValue(electrician, Map.class));
        } catch (UnsupportedOperationException noSdk) {
            log.info("[Seeder] Firebase Admin SDK not present or Firestore unavailable; skipping services seed");
        } catch (Exception e) {
            log.warn("[Seeder] Failed to seed services catalog: {}", e.getMessage());
        }
    }
}
