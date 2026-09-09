package edu.camserver.app.model;

import jakarta.persistence.Entity;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FrameMeta {
    private Double clientTs;
    private Double serverTs;
    private Integer latencyMs;
    private String tsFormat;
}
