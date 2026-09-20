package com.wjz.worldsmith.content.story;

/** Pure view maths. Coordinates are already discovered server marker positions, never authored guesses. */
public final class StoryNavigation {
    private StoryNavigation() {}
    public enum Bearing { AHEAD, RIGHT, BEHIND, LEFT, ARRIVED }
    public static Bearing bearing(double deltaX, double deltaZ, float yaw) {
        if (!Double.isFinite(deltaX) || !Double.isFinite(deltaZ) || !Float.isFinite(yaw)) throw new IllegalArgumentException("Invalid navigation vector");
        if (deltaX * deltaX + deltaZ * deltaZ <= 25) return Bearing.ARRIVED;
        double angle = Math.toDegrees(Math.atan2(-deltaX, deltaZ)) - yaw;
        angle = ((angle + 180) % 360 + 360) % 360 - 180;
        if (Math.abs(angle) <= 35) return Bearing.AHEAD;
        if (Math.abs(angle) >= 145) return Bearing.BEHIND;
        return angle > 0 ? Bearing.RIGHT : Bearing.LEFT;
    }
    public static int distance(double x, double y, double z, StoryProtocol.Place place) {
        return (int)Math.min(Integer.MAX_VALUE, Math.round(Math.sqrt(square(place.x() + .5 - x) + square(place.y() - y) + square(place.z() + .5 - z))));
    }
    private static double square(double x) { return x * x; }
}
