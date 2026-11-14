package app.carbuddy;

final class TripMeter {
    private static final long MAX_GAP_NS = 5_000_000_000L;
    static final int SPEED_CUE_KPH = 80;
    private static final float HARD_BRAKING_MPS2 = 3.5f;
    private static final long MIN_BRAKE_INTERVAL_NS = 100_000_000L;
    private static final long MAX_BRAKE_INTERVAL_NS = 1_000_000_000L;
    record Summary(float km, int seconds, boolean speedExceeded, boolean hardBraking) {}

    private boolean parked, active, incomplete, speedExceeded, hardBraking;
    private long startNs, previousNs, movingNs;
    private float previousSpeed;
    private double meters;
    private Summary last;

    void gear(boolean park, long timeNs) {
        if (park == parked) return;
        if (park) {
            if (active && !incomplete && meters > 0 && timeNs - previousNs <= MAX_GAP_NS) {
                last = new Summary((float) (meters / 1000), (int) (movingNs / 1_000_000_000L),
                        speedExceeded, hardBraking);
            }
            active = false;
        } else if (parked) {
            active = true;
            incomplete = speedExceeded = hardBraking = false;
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
        speedExceeded |= metersPerSecond > SPEED_CUE_KPH / 3.6f;
        if (previousNs == 0) {
            if (timeNs - startNs > MAX_GAP_NS) incomplete = true;
        } else {
            long gap = timeNs - previousNs;
            if (gap > MAX_GAP_NS) incomplete = true;
            else if (speed > 0 || previousSpeed > 0) {
                meters += (speed + previousSpeed) * 0.5 * (gap / 1e9);
                movingNs += gap;
                if (gap >= MIN_BRAKE_INTERVAL_NS && gap <= MAX_BRAKE_INTERVAL_NS) {
                    hardBraking |= previousSpeed - speed >= HARD_BRAKING_MPS2 * (gap / 1e9);
                }
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
