package app.carbuddy;

final class TripMeter {
    private static final long MAX_GAP_NS = 5_000_000_000L;
    static final int SPEED_CUE_KPH = 80;
    private static final float HARD_BRAKING_MPS2 = 3.5f;
    private static final long MIN_BRAKE_INTERVAL_NS = 100_000_000L;
    private static final long MAX_BRAKE_INTERVAL_NS = 1_000_000_000L;
    record Summary(float km, int seconds, boolean speedExceeded, boolean hardBraking) {}
    record Checkpoint(boolean active, boolean incomplete, double meters, long movingNs,
                      boolean speedExceeded, boolean hardBraking) {}

    private Boolean parked;
    private boolean active, incomplete, speedExceeded, hardBraking;
    private long startNs, previousNs, movingNs;
    private float previousSpeed;
    private double meters;

    TripMeter(Checkpoint saved) {
        active = saved.active();
        incomplete = saved.incomplete();
        meters = saved.meters();
        movingNs = saved.movingNs();
        speedExceeded = saved.speedExceeded();
        hardBraking = saved.hardBraking();
    }

    boolean gear(boolean park, long timeNs) {
        if (Boolean.valueOf(park).equals(parked)) return false;
        if (park) {
            if (active && Boolean.FALSE.equals(parked)) {
                incomplete |= previousNs < 0 || timeNs - previousNs > MAX_GAP_NS;
            }
        } else {
            if (Boolean.TRUE.equals(parked) && !active) {
                active = true;
                incomplete = speedExceeded = hardBraking = false;
                movingNs = 0;
                meters = 0;
            } else if (parked == null && active) {
                incomplete = true;
            }
            startNs = timeNs;
            previousNs = -1;
        }
        parked = park;
        return true;
    }

    void speed(long timeNs, float metersPerSecond) {
        if (!active || !Boolean.FALSE.equals(parked) || incomplete
                || timeNs < startNs || timeNs <= previousNs) return;
        float speed = Math.abs(metersPerSecond);
        if (!Float.isFinite(speed)) { incomplete = true; return; }
        speedExceeded |= metersPerSecond > SPEED_CUE_KPH / 3.6f;
        if (previousNs < 0) {
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

    void signalLost(boolean gearSignal) {
        if (active && Boolean.FALSE.equals(parked)) incomplete = true;
        if (gearSignal) parked = null;
    }

    boolean parked() { return Boolean.TRUE.equals(parked); }
    boolean active() { return active; }
    boolean incomplete() { return incomplete; }

    boolean finish() {
        if (!parked() || !active) return false;
        active = false;
        return true;
    }

    Summary summary() {
        if (incomplete || meters <= 0) return null;
        return new Summary((float) (meters / 1000), (int) (movingNs / 1_000_000_000L),
                speedExceeded, hardBraking);
    }

    Checkpoint checkpoint() {
        // The last persisted totals cover completed legs through the most recent park event.
        return new Checkpoint(active, incomplete || (active && !parked()), meters, movingNs,
                speedExceeded, hardBraking);
    }
}
