package app.carbuddy;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import ai.pleos.playground.navi.constants.NaviErrorCode;
import ai.pleos.playground.navi.data.DestinationArrivedInfo;
import ai.pleos.playground.navi.data.RouteStartInfo;
import ai.pleos.playground.navi.helper.NaviHelper;
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener;
import java.util.function.IntConsumer;
import kotlin.Unit;

final class NavigationEvents implements NaviHelperEventListener {
    private static final String TAG = "CarBuddyNavigation";
    private final NaviHelper sdk;
    private final Handler handler;
    private final Runnable arrival, newRoute;
    private final IntConsumer speedLimit;
    private boolean closed, listening;

    NavigationEvents(Context context, Handler handler, Runnable arrival, Runnable newRoute, IntConsumer speedLimit) {
        sdk = new NaviHelper(context);
        this.handler = handler;
        this.arrival = arrival;
        this.newRoute = newRoute;
        this.speedLimit = speedLimit;
    }

    void start() {
        sdk.initialize(() -> {
            deliver(() -> {
                try {
                    sdk.addListener(this);
                    listening = true;
                    Log.i(TAG, "Connected to Pleos Navigation");
                } catch (RuntimeException e) {
                    Log.w(TAG, "Cannot subscribe to navigation events", e);
                }
            });
            return Unit.INSTANCE;
        }, failure -> {
            Log.w(TAG, "Pleos Navigation unavailable", failure);
            return Unit.INSTANCE;
        });
    }

    @Override public void onDestinationArrived(DestinationArrivedInfo info) {
        if (info != null) deliver(() -> {
            Log.d(TAG, "Final destination arrived");
            arrival.run();
        });
    }

    @Override public void onRouteStarted(RouteStartInfo info) {
        deliver(() -> {
            Log.d(TAG, "Route guidance started");
            newRoute.run();
        });
    }
    @Override public void onRouteEnded() { deliver(() -> speedLimit.accept(0)); }
    @Override public void onRouteCancelled() { deliver(() -> speedLimit.accept(0)); }
    @Override public void onCurrentRoadSpeedLimit(int limit) { deliver(() -> speedLimit.accept(limit)); }
    @Override public void onError(NaviErrorCode error) { deliver(() -> speedLimit.accept(0)); }

    private void deliver(Runnable event) {
        handler.post(() -> { if (!closed) event.run(); });
    }

    void close() {
        closed = true;
        if (listening) sdk.removeListener(this);
        sdk.release();
    }
}
