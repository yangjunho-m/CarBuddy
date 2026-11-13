package app.carbuddy;

final class TripMeter {
    private static final long MAX_GAP_NS = 5_000_000_000L;
    record Summary(float km, int seconds) {}

    private boolean parked, active, incomplete;
    private long startNs, previousNs, movingNs;
    private float previousSpeed;
    private double meters;
    private Summary last;

    void gear(boolean park, long timeNs) {
        if (park == parked) return;
        if (park) {
            if (active && !incomplete && meters > 0 && timeNs - previousNs <= MAX_GAP_NS) {
                last = new Summary((float) (meters / 1000), (int) (movingNs / 1_000_000_000L));
            }
            active = false;
        } else if (parked) {
            active = true;
            incomplete = false;
            startNs = timeNs;
            previousNs = movingNs = 0;
            meters = 0;
            last = null;
        }
        parked = park;
    }

    void speed(long timeNs, float metersPerSecond) {
        if (!active || timeNs < startNs || timeNs <= previousNs) return;
        float speed = Math.abs(metersPerSecond);
        if (!Float.isFinite(speed)) { invalidate(); return; }
        if (previousNs == 0) {
            if (timeNs - startNs > MAX_GAP_NS) incomplete = true;
        } else {
            long gap = timeNs - previousNs;
            if (gap > MAX_GAP_NS) incomplete = true;
            else if (speed > 0 || previousSpeed > 0) {
                meters += (speed + previousSpeed) * 0.5 * (gap / 1e9);
                movingNs += gap;
            }
        }
        previousNs = timeNs;
        previousSpeed = speed;
    }

    void invalidate() {
        parked = active = false;
        last = null;
    }

    Summary last() { return last; }
}
