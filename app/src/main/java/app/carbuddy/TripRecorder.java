package app.carbuddy;

import android.car.VehicleGear;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.util.Log;

final class TripRecorder implements CarPropertyManager.CarPropertyEventCallback {
    private final CarPropertyManager manager;
    private final TripMeter meter = new TripMeter();
    private final Runnable changed;
    private boolean running;

    TripRecorder(CarPropertyManager manager, Runnable changed) {
        this.manager = manager;
        this.changed = changed;
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
            var before = meter.last();
            if (gear == VehicleGear.GEAR_UNKNOWN) meter.invalidate();
            else meter.gear(gear == VehicleGear.GEAR_PARK, value.getTimestamp());
            if (before != meter.last()) changed.run();
        }
    }

    @Override public void onErrorEvent(int property, int area) {
        if (!running) return;
        meter.invalidate();
        changed.run();
    }

    TripMeter.Summary last() { return meter.last(); }

    void close() {
        running = false;
        meter.invalidate();
        try {
            manager.unregisterCallback(this);
        } catch (RuntimeException e) {
            Log.w("CarBuddy", "Cannot unregister trip listener", e);
        }
    }
}
