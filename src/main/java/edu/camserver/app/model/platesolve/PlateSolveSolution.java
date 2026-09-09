package edu.camserver.app.model.platesolve;

public record PlateSolveSolution(
        boolean solved,
        Double fieldCenterRaDeg,
        Double fieldCenterDecDeg,
        Double fieldWidthDeg,
        Double fieldHeightDeg,
        Double siteLatitudeDeg,
        Double siteLongitudeDeg,
        String wcsFile,
        String solverLog
) {
}
