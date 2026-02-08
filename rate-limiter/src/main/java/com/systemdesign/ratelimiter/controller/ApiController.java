package com.systemdesign.ratelimiter.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    @GetMapping("/search")
    public ResponseEntity<Map<String, String>> search() {
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Search result"));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login() {
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Login successful"));
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register() {
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Registration successful"));
    }
}
