package edu.camserver.app.controller;

import edu.camserver.app.model.Image;
import edu.camserver.app.service.ImageService;
import jakarta.persistence.NoResultException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class FeatController {
    final private ImageService imageService;

    public FeatController(ImageService imageService) {
        this.imageService = imageService;
    }

    @RequestMapping("/feat")
    public ResponseEntity<?> setFeatured(@RequestParam long imgId, @RequestParam Boolean feat) {
        try {
            Image image = imageService.setFeatured(imgId, feat);
            return ResponseEntity.ok(image.toString());
        } catch (NoResultException e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }
}
