package com.logicnativesolution.servemeapi.service;

import com.logicnativesolution.servemeapi.entities.Document;
import com.logicnativesolution.servemeapi.repository.DocumentRepository;
import com.logicnativesolution.servemeapi.util.ImageUtils;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
@AllArgsConstructor
public class FileUploadService {
    private final ImageUtils imageUtils;
    private DocumentRepository documentRepository;

    public void uploadFile(MultipartFile frontIdImage, MultipartFile backIdImage, MultipartFile faceImageWithId) throws IOException {
        Document documents = new Document();
        documents.setFrontIdImage(imageUtils.compressImage(frontIdImage.getBytes()));
        documents.setBackIdImage(imageUtils.compressImage(backIdImage.getBytes()));
        documents.setFaceImageHoldingId(imageUtils.compressImage(faceImageWithId.getBytes()));
        documentRepository.save(documents);

    }
}
