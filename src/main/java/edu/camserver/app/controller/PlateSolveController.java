package edu.camserver.app.controller;

import edu.camserver.app.model.platesolve.PlateSolveResult;
import edu.camserver.app.service.PlateSolveService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/plate-solve")
public class PlateSolveController {
    private final PlateSolveService plateSolveService;

    public PlateSolveController(PlateSolveService plateSolveService) {
        this.plateSolveService = plateSolveService;
    }

    @GetMapping("/{imgId}")
    public PlateSolveResult status(@PathVariable long imgId) {
        return plateSolveService.getStatus(imgId);
    }

    @PostMapping("/{imgId}")
    public PlateSolveResult start(
            @PathVariable long imgId,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(defaultValue = "false") boolean wait) {
        return plateSolveService.start(imgId, force, wait);
    }
}
