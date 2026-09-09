package edu.camserver.app.repository;

import edu.camserver.app.model.Camera;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CameraRepository extends JpaRepository<Camera, Long> {

}
