package app.carbuddy;

import android.car.VehicleGear;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.SharedPreferences;
import android.util.Log;

final class TripRecorder implements CarPropertyManager.CarPropertyEventCallback {
    private final CarPropertyManager manager;
    private final SharedPreferences saved;
    private final TripMeter meter;
    private final Runnable changed;
    private boolean running;

    TripRecorder(CarPropertyManager manager, SharedPreferences saved, Runnable changed) {
        this.manager = manager;
        this.saved = saved;
        this.changed = changed;
        meter = new TripMeter(new TripMeter.Checkpoint(saved.getBoolean("active", false),
                saved.getBoolean("incomplete", false),
                Double.longBitsToDouble(saved.getLong("meters", 0)), saved.getLong("moving_ns", 0),
                saved.getBoolean("speed_exceeded", false), saved.getBoolean("hard_braking", false)));
    }

    void start() {
        try {
            running = manager.registerCallback(this, VehiclePropertyIds.PERF_VEHICLE_SPEED, 5f)
                    && manager.registerCallback(this, VehiclePropertyIds.GEAR_SELECTION,
                    CarPropertyManager.SENSOR_RATE_ONCHANGE);
        } catch (RuntimeException e) {
            Log.w("CarBuddy", "Trip recording unavailable", e);
        }
        if (!running) close();
    }

    @Override public void onChangeEvent(CarPropertyValue value) {
        if (!running) return;
        if (value.getStatus() != CarPropertyValue.STATUS_AVAILABLE) {
            onErrorEvent(value.getPropertyId(), value.getAreaId());
            return;
        }
        if (value.getPropertyId() == VehiclePropertyIds.PERF_VEHICLE_SPEED
                && value.getValue() instanceof Float speed) {
            meter.speed(value.getTimestamp(), speed);
        } else if (value.getPropertyId() == VehiclePropertyIds.GEAR_SELECTION
                && value.getValue() instanceof Integer gear) {
            if (gear == VehicleGear.GEAR_UNKNOWN) {
                onErrorEvent(value.getPropertyId(), value.getAreaId());
            } else if (meter.gear(gear == VehicleGear.GEAR_PARK, value.getTimestamp())) {
                save();
                changed.run();
            }
        }
    }

    @Override public void onErrorEvent(int property, int area) {
        if (!running) return;
        meter.signalLost(property == VehiclePropertyIds.GEAR_SELECTION);
        save();
        changed.run();
    }

    boolean active() { return meter.active(); }
    boolean parked() { return meter.parked(); }
    boolean incomplete() { return meter.incomplete(); }
    TripMeter.Summary summary() { return meter.summary(); }

    boolean finish() {
        if (!meter.finish()) return false;
        save();
        return true;
    }

    private void save() {
        TripMeter.Checkpoint checkpoint = meter.checkpoint();
        saved.edit().putBoolean("active", checkpoint.active())
                .putBoolean("incomplete", checkpoint.incomplete())
                .putLong("meters", Double.doubleToRawLongBits(checkpoint.meters()))
                .putLong("moving_ns", checkpoint.movingNs())
                .putBoolean("speed_exceeded", checkpoint.speedExceeded())
                .putBoolean("hard_braking", checkpoint.hardBraking())
                .apply();
    }

    void close() {
        running = false;
        try {
            manager.unregisterCallback(this);
        } catch (RuntimeException e) {
            Log.w("CarBuddy", "Cannot unregister trip listener", e);
        }
    }
}
