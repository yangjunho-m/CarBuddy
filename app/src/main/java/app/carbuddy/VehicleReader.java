package app.carbuddy;

import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.os.Handler;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class VehicleReader implements CarPropertyManager.CarPropertyEventCallback {
    private static final String TAG = "CarBuddyVehicle";
    private static final int GLOBAL_AREA = 0;
    private final Handler handler;
    private final CarPropertyManager manager;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Consumer<Integer> fuelChanged;
    private Float capacityMl;
    private boolean closed;

    VehicleReader(CarPropertyManager manager, Handler handler, Consumer<Integer> fuelChanged) {
        this.manager = manager;
        this.handler = handler;
        this.fuelChanged = fuelChanged;
    }

    void watchFuel() {
        try {
            manager.registerCallback(this, VehiclePropertyIds.FUEL_LEVEL, 1f);
        } catch (RuntimeException e) {
            Log.w(TAG, "Fuel subscription unavailable", e);
        }
    }

    @Override public void onChangeEvent(CarPropertyValue value) {
        if (closed) return;
        fuelChanged.accept(TripStory.fuelPercent(availableFloat(value), capacityMl));
    }

    @Override public void onErrorEvent(int property, int area) {
        if (!closed) fuelChanged.accept(null);
    }

    void readFuel(Consumer<Integer> result) {
        if (closed) return;
        worker.execute(() -> {
            Float capacity = read(VehiclePropertyIds.INFO_FUEL_CAPACITY);
            Float level = read(VehiclePropertyIds.FUEL_LEVEL);
            handler.post(() -> {
                if (closed) return;
                capacityMl = capacity;
                result.accept(TripStory.fuelPercent(level, capacity));
            });
        });
    }

    private Float read(int property) {
        try {
            return availableFloat(manager.getProperty(Float.class, property, GLOBAL_AREA));
        } catch (RuntimeException e) {
            Log.w(TAG, "Fuel data unavailable for property " + property, e);
            return null;
        }
    }

    private static Float availableFloat(CarPropertyValue<?> value) {
        return value != null && value.getStatus() == CarPropertyValue.STATUS_AVAILABLE
                && value.getValue() instanceof Float amount ? amount : null;
    }

    void close() {
        closed = true;
        worker.shutdownNow();
        try {
            manager.unregisterCallback(this);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot unregister fuel listener", e);
        }
    }
}
