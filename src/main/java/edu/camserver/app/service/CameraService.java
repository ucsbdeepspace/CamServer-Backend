package edu.camserver.app.service;

import edu.camserver.app.model.Camera;
import edu.camserver.app.repository.CameraRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CameraService {
    private final CameraRepository cameraRepository;

    public CameraService(CameraRepository cameraRepository) {
        this.cameraRepository = cameraRepository;
    }

    public List<Camera> getSites() {
        return cameraRepository.findAll();
    }
}
