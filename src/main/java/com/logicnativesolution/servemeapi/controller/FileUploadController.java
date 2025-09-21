package com.logicnativesolution.servemeapi.controller;

import com.logicnativesolution.servemeapi.service.FileUploadService;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/file")
@AllArgsConstructor
public class FileUploadController {

    private final FileUploadService fileUploadService;


    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(@RequestParam MultipartFile frontIdImage,@RequestParam MultipartFile backIdImage,@RequestParam MultipartFile faceImageWithId) throws IOException {
        fileUploadService.uploadFile(frontIdImage,backIdImage,faceImageWithId);
        return ResponseEntity.ok("Images uploaded successfully");
    }


}
