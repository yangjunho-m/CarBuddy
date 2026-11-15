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

    void readBattery(Consumer<Integer> result) {
        if (closed) return;
        class Request {
            boolean delivered;
            void finish(Integer battery) {
                if (!delivered && !closed) {
                    delivered = true;
                    result.accept(battery);
                }
            }
        }
        Request r = new Request();
        handler.postDelayed(() -> r.finish(null), 3000);
        var ev = vehicle.getEvBattery();
        Function1<Exception, Unit> failure = e -> {
            Log.w(TAG, "Battery unavailable", e);
            handler.post(() -> r.finish(null));
            return Unit.INSTANCE;
        };
        try {
            ev.checkEvBatteryStateOfChargeCapabilities(config -> {
                handler.post(() -> {
                    if (closed || r.delivered) return;
                    if (!readable(config)) {
                        r.finish(null);
                        return;
                    }
                    try {
                        ev.getEvBatteryStateOfCharge(value -> {
                            handler.post(() -> r.finish(value));
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
