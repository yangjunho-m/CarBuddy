package app.carbuddy;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import java.util.function.Consumer;
import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.api.model.capability.AccessType;
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig;
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

final class VehicleReader {
    private static final String TAG = "CarBuddyVehicle";
    private final Handler handler;
    private final Vehicle vehicle;
    private boolean closed;
    private EvBatteryStateOfChargeListener batteryListener;

    VehicleReader(Context context, Handler handler) {
        this.handler = handler;
        vehicle = new Vehicle(context.getApplicationContext());
        vehicle.initialize();
    }

    interface Read<T> {
        void call(Function1<? super T, Unit> good, Function1<? super Exception, Unit> bad);
    }

    void watchBattery(Consumer<Integer> consumer) {
        var ev = vehicle.getEvBattery();
        ev.checkEvBatteryStateOfChargeCapabilities(config -> {
            handler.post(() -> {
                if (closed || !readable(config)) return;
                var listener = new EvBatteryStateOfChargeListener() {
                    @Override public void onEvBatteryStateOfChargeUpdated(Integer value) {
                        handler.post(() -> { if (!closed) consumer.accept(value); });
                    }
                    @Override public void onFailed(Exception e) { Log.w(TAG, "Battery update unavailable", e); }
                };
                try {
                    ev.registerEvBatteryStateOfCharge(listener);
                    batteryListener = listener;
                } catch (RuntimeException e) { Log.w(TAG, "Cannot subscribe to battery", e); }
            });
            return Unit.INSTANCE;
        }, e -> {
            Log.w(TAG, "Cannot check battery subscription", e);
            return Unit.INSTANCE;
        });
    }

    private static boolean readable(CapabilityConfig config) {
        AccessType access = config == null ? null : config.getAccessType();
        return access == AccessType.READ || access == AccessType.READ_WRITE;
    }

    void read(Consumer<TripStory> result) {
        if (closed) return;
        class Request {
            Integer battery;
            Float km;
            Integer minutes;
            int pending = 3;
            boolean delivered;
            void finish() {
                if (!delivered && !closed) {
                    delivered = true;
                    result.accept(new TripStory(battery, km, minutes != null && minutes >= 0
                            && minutes <= Integer.MAX_VALUE / 60 ? minutes * 60 : null));
                }
            }
            void received() { if (--pending == 0) finish(); }
        }
        Request r = new Request();
        handler.postDelayed(r::finish, 3000);
        var ev = vehicle.getEvBattery();
        var trip = vehicle.getOdometer();
        this.<Integer>get("battery", ev::checkEvBatteryStateOfChargeCapabilities,
                ev::getEvBatteryStateOfCharge, v -> { r.battery = v; r.received(); });
        this.<Float>get("distance", trip::checkTripCurrentDrivingDistanceCapabilities,
                trip::getTripCurrentDrivingDistance, v -> { r.km = v; r.received(); });
        this.<Integer>get("duration", trip::checkTripCurrentDrivingTimeCapabilities,
                trip::getTripCurrentDrivingTime, v -> { r.minutes = v; r.received(); });
    }

    private <T> void get(String field, Read<CapabilityConfig> capability, Read<T> get, Consumer<T> done) {
        Function1<Exception, Unit> failure = e -> {
            Log.w(TAG, field + " unavailable: " + e);
            handler.post(() -> { if (!closed) done.accept(null); });
            return Unit.INSTANCE;
        };
        try {
            capability.call(config -> {
                handler.post(() -> {
                    if (closed) return;
                    if (!readable(config)) {
                        Log.d(TAG, field + " has no read capability");
                        done.accept(null);
                        return;
                    }
                    try {
                        get.call(value -> {
                            handler.post(() -> { if (!closed) done.accept(value); });
                            return Unit.INSTANCE;
                        }, failure);
                    } catch (RuntimeException e) { failure.invoke(e); }
                });
                return Unit.INSTANCE;
            }, failure);
        } catch (RuntimeException e) { failure.invoke(e); }
    }

    void close() {
        closed = true;
        try {
            if (batteryListener != null) vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(batteryListener);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot unregister battery listener", e);
        } finally {
            vehicle.release();
        }
    }
}
