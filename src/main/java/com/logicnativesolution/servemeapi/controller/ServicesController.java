package com.logicnativesolution.servemeapi.controller;

import com.logicnativesolution.servemeapi.model.firestore.ServiceDoc;
import com.logicnativesolution.servemeapi.service.FirestoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/services")
@RequiredArgsConstructor
public class ServicesController {

    private final FirestoreService firestoreService;

    @GetMapping
    public ResponseEntity<List<ServiceDoc>> list() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> raw = firestoreService.listCollection("services");
        List<ServiceDoc> out = new ArrayList<>(raw.size());
        for (Map<String, Object> m : raw) {
            out.add(toServiceDocSafe(m));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Defensive mapper from Firestore map to ServiceDoc. Tolerates missing or differently-typed fields
     * so that all documents are returned even if one has slightly different schema (e.g., numbers as Double/String).
     */
    private static ServiceDoc toServiceDocSafe(Map<String, Object> m) {
        if (m == null) return new ServiceDoc();
        ServiceDoc.ServiceDocBuilder b = ServiceDoc.builder();
        // id
        Object id = m.get("id");
        b.id(id == null ? null : String.valueOf(id));
        // displayName
        Object dn = m.get("displayName");
        b.displayName(dn == null ? null : String.valueOf(dn));
        // basePrice (expect integer cents; coerce from Number/String)
        Long basePrice = null;
        Object bp = m.get("basePrice");
        if (bp instanceof Number n) {
            basePrice = n.longValue();
        } else if (bp instanceof String s) {
            try { basePrice = (long) Double.parseDouble(s); } catch (Exception ignore) {}
        }
        b.basePrice(basePrice);
        // min/max radius
        Integer minR = null, maxR = null;
        Object min = m.get("minRadiusKm");
        if (min instanceof Number n) minR = n.intValue();
        else if (min instanceof String s) { try { minR = (int) Double.parseDouble(s); } catch (Exception ignore) {} }
        Object max = m.get("maxRadiusKm");
        if (max instanceof Number n) maxR = n.intValue();
        else if (max instanceof String s) { try { maxR = (int) Double.parseDouble(s); } catch (Exception ignore) {} }
        b.minRadiusKm(minR);
        b.maxRadiusKm(maxR);
        // addOns
        Object addOns = m.get("addOns");
        if (addOns instanceof List<?> list) {
            List<ServiceDoc.AddOn> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?,?> mm) {
                    String aid = mm.get("id") == null ? null : String.valueOf(mm.get("id"));
                    String label = mm.get("label") == null ? null : String.valueOf(mm.get("label"));
                    Long price = null;
                    Object p = mm.get("price");
                    if (p instanceof Number pn) price = pn.longValue();
                    else if (p instanceof String ps) { try { price = (long) Double.parseDouble(ps); } catch (Exception ignore) {} }
                    out.add(ServiceDoc.AddOn.builder().id(aid).label(label).price(price).build());
                }
            }
            b.addOns(out);
        }
        return b.build();
    }
}
