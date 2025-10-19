package com.logicnativesolution.servemeapi.controller;

import com.logicnativesolution.servemeapi.dto.jobs.CreateJobRequest;
import com.logicnativesolution.servemeapi.dto.jobs.SendMessageRequest;
import com.logicnativesolution.servemeapi.dto.jobs.UpdateStatusRequest;
import com.logicnativesolution.servemeapi.service.JobsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobsController {

    private final JobsService jobsService;

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateJobRequest req, Principal p) {
        String uid = p != null ? p.getName() : null;
        if (uid == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        try {
            Map<String, Object> created = jobsService.create(req, uid);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (UnsupportedOperationException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to create job"));
        }
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(name = "role", required = false) String role, Principal p) {
        String uid = p != null ? p.getName() : null;
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            List<Map<String, Object>> items = jobsService.list(role, uid);
            return ResponseEntity.ok(items);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to list jobs"));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id, Principal p) {
        String uid = p != null ? p.getName() : null;
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            Map<String, Object> data = jobsService.get(id, uid);
            return ResponseEntity.ok(data);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (SecurityException se) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (UnsupportedOperationException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to get job"));
        }
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<?> accept(@PathVariable String id, Principal p) {
        String uid = p != null ? p.getName() : null;
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            Map<String, Object> updated = jobsService.accept(id, uid);
            return ResponseEntity.ok(updated);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (UnsupportedOperationException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to accept job"));
        }
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable String id, @RequestBody UpdateStatusRequest req, Principal p) {
        String uid = p != null ? p.getName() : null;
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            Map<String, Object> updated = jobsService.updateStatus(id, req.getStatus(), uid);
            return ResponseEntity.ok(updated);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (SecurityException se) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (UnsupportedOperationException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to update status"));
        }
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<?> sendMessage(@PathVariable String id, @RequestBody SendMessageRequest req, Principal p) {
        String uid = p != null ? p.getName() : null;
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            Map<String, Object> msg = jobsService.sendMessage(id, req, uid);
            return ResponseEntity.status(HttpStatus.CREATED).body(msg);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (SecurityException se) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (UnsupportedOperationException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to send message"));
        }
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<?> listMessages(@PathVariable String id, Principal p) {
        String uid = p != null ? p.getName() : null;
        if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            List<Map<String, Object>> msgs = jobsService.listMessages(id, uid);
            return ResponseEntity.ok(msgs);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (SecurityException se) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (UnsupportedOperationException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to list messages"));
        }
    }
}
