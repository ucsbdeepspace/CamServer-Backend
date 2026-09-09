package edu.camserver.app.model.platesolve;

import java.util.List;

public record PlateSolveStar(
        int id,
        double x,
        double y,
        double cropX,
        double cropY,
        int brightness,
        Double raDeg,
        Double decDeg,
        String name,
        Double magnitude,
        Double catalogMatchDistanceArcsec,
        List<PlateSolveStarIdentifier> identifiers,
        List<PlateSolveStarLink> links,
        boolean skyCoordinateSolved
) {
}
