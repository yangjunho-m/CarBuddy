package app.carbuddy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.car.Car;
import android.car.VehicleGear;
import android.car.VehiclePropertyIds;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import java.util.LinkedHashSet;
import java.util.Set;

public final class FloatingBuddyService extends Service {
    private static final String TAG = "CarBuddyFloating";
    private static final String X = "floating_position_x";
    private static final String Y = "floating_position_y";
    static final String OPEN_DIARY = "open_arrival_diary";
    static final String OPEN_SETTINGS = "open_character_settings";
    private static final int WIDTH_DP = 320;
    private static final int HEIGHT_DP = 280;
    private static final int DIARY_BUTTON_DP = 64;
    private static FloatingBuddyService running;
    private static int visibleActivities;
    private static final Set<Runnable> tripListeners = new LinkedHashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private Car car;
    private CarUxRestrictionsManager restrictions;
    private CarPropertyManager properties;
    private TripRecorder recorder;
    private NavigationEvents navigation;
    private int roadSpeedLimit;
    private boolean unrestricted, parked;
    private Context windowContext;
    private WindowManager windows;
    private WindowManager.LayoutParams placement;
    private Rect safeArea;
    private MascotView buddy;
    private LinearLayout companion;
    private Button diaryPrompt;

    static void activityVisible(boolean visible) {
        visibleActivities += visible ? 1 : -1;
        if (running != null) running.updateWindow();
    }

    static TripRecorder trip() { return running == null ? null : running.recorder; }
    static void addTripListener(Runnable listener) { tripListeners.add(listener); }
    static void removeTripListener(Runnable listener) { tripListeners.remove(listener); }

    private void tripChanged() {
        updateWindow();
        for (Runnable listener : tripListeners) listener.run();
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> {
        if (CharacterArt.CHOICE.equals(key) || CharacterArt.FLOATING.equals(key)) updateWindow();
    };

    private final CarPropertyManager.CarPropertyEventCallback gearListener =
            new CarPropertyManager.CarPropertyEventCallback() {
        @Override public void onChangeEvent(CarPropertyValue value) {
            parked = value.getStatus() == CarPropertyValue.STATUS_AVAILABLE
                    && Integer.valueOf(VehicleGear.GEAR_PARK).equals(value.getValue());
            updateWindow();
        }

        @Override public void onErrorEvent(int property, int area) {
            parked = false;
            updateWindow();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        running = this;
        preferences = CharacterArt.preferences(this);
        preferences.registerOnSharedPreferenceChangeListener(changes);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!preferences.getBoolean(CharacterArt.PICKED, false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(1, notification());
        if (car == null) {
            car = Car.createCar(this, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, (connected, ready) -> {
                if (running != this) return;
                stopWatchingCar();
                if (ready) watchCar(connected);
                tripChanged();
            });
        }
        startRecorder();
        if (navigation == null) {
            try {
                navigation = new NavigationEvents(this, handler, () -> {
                    if (recorder != null) recorder.destinationArrived();
                }, () -> {
                    roadSpeedLimit = 0;
                    if (recorder != null) recorder.newRoute();
                }, limit -> {
                    roadSpeedLimit = limit;
                    if (recorder != null) recorder.roadSpeedLimit(limit);
                });
                navigation.start();
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot connect to navigation", e);
            }
        }
        updateWindow();
        return START_STICKY;
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        String channel = "floating_buddy";
        manager.createNotificationChannel(new NotificationChannel(channel, "떠 있는 카버디",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, openApp(false, false), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, channel)
                .setSmallIcon(R.drawable.carbuddy_icon)
                .setContentTitle("카버디와 함께")
                .setContentText("주행을 기록하고 주차 중 캐릭터를 표시해요.")
                .setOngoing(true)
                .setContentIntent(open)
                .build();
    }

    private Intent openApp(boolean diary, boolean settings) {
        return new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(OPEN_DIARY, diary).putExtra(OPEN_SETTINGS, settings);
    }

    private boolean diaryAvailable() { return recorder != null && recorder.diaryReady(); }

    private void watchCar(Car connected) {
        try {
            restrictions = (CarUxRestrictionsManager) connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
            properties = (CarPropertyManager) connected.getCarManager(Car.PROPERTY_SERVICE);
            if (restrictions == null || properties == null) return;
            restrictions.registerListener(value -> {
                unrestricted = value != null && !value.isRequiresDistractionOptimization();
                updateWindow();
            });
            var current = restrictions.getCurrentCarUxRestrictions();
            unrestricted = current != null && !current.isRequiresDistractionOptimization();
            properties.registerCallback(gearListener, VehiclePropertyIds.GEAR_SELECTION,
                    CarPropertyManager.SENSOR_RATE_ONCHANGE);
            startRecorder();
        } catch (RuntimeException e) {
            stopWatchingCar();
            Log.w(TAG, "Cannot verify parked state", e);
        }
    }

    private void startRecorder() {
        if (recorder != null || properties == null
                || checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) return;
        recorder = new TripRecorder(properties, getSharedPreferences("journey", MODE_PRIVATE),
                this::tripChanged);
        recorder.roadSpeedLimit(roadSpeedLimit);
        recorder.start();
        tripChanged();
    }

    private void stopWatchingCar() {
        parked = unrestricted = false;
        if (recorder != null) {
            recorder.close();
            recorder = null;
        }
        try {
            if (restrictions != null) restrictions.unregisterListener();
            if (properties != null) properties.unregisterCallback(gearListener);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot unregister a car callback", e);
        }
        restrictions = null;
        properties = null;
    }

    private void updateWindow() {
        if (!preferences.getBoolean(CharacterArt.FLOATING, false) || !Settings.canDrawOverlays(this)) {
            removeWindow();
            return;
        }
        if (!parked || !unrestricted || visibleActivities > 0 || (recorder != null && recorder.active())) {
            removeWindow();
            return;
        }
        if (companion == null) addWindow();
        if (companion != null) {
            CharacterArt art = CharacterArt.saved(preferences.getString(CharacterArt.CHOICE, ""));
            boolean diaryReady = diaryAvailable();
            companion.setClickable(!diaryReady);
            companion.setContentDescription(diaryReady ? null : art.label + ". 끌어서 위치 이동, 탭하여 설정 열기");
            buddy.setVisibility(diaryReady ? MascotView.GONE : MascotView.VISIBLE);
            diaryPrompt.setVisibility(diaryReady ? Button.VISIBLE : Button.GONE);
            int height = dp(diaryReady ? DIARY_BUTTON_DP : HEIGHT_DP);
            if (placement.height != height) {
                placement.height = height;
                place(placement.x, placement.y);
            }
            if (!diaryReady) buddy.show(art, Expression.NEUTRAL);
        }
    }

    private void addWindow() {
        Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) return;
        windowContext = createDisplayContext(display);
        if (Build.VERSION.SDK_INT >= 30) {
            windowContext = windowContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        }
        windows = windowContext.getSystemService(WindowManager.class);
        boolean diaryReady = diaryAvailable();
        placement = new WindowManager.LayoutParams(dp(WIDTH_DP), dp(diaryReady ? DIARY_BUTTON_DP : HEIGHT_DP),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        placement.gravity = Gravity.TOP | Gravity.LEFT;
        placement.setTitle("CarBuddy Companion");
        if (Build.VERSION.SDK_INT >= 30) placement.setFitInsetsTypes(0);
        safeArea = availableArea();
        // The left third of the wide Pleos display belongs to its driving view.
        int defaultX = safeArea.left + Math.round(safeArea.width() * .406f) - placement.width / 2;
        place(preferences.getInt(X, defaultX), preferences.getInt(Y, safeArea.top));

        LinearLayout view = new LinearLayout(windowContext);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setGravity(Gravity.CENTER);
        view.setOnClickListener(v -> {
            if (parked && unrestricted && !diaryAvailable() && Settings.canDrawOverlays(this)) {
                startActivity(openApp(false, true));
            }
        });
        view.setOnTouchListener(new Drag());
        MascotView character = new MascotView(windowContext);
        character.setImportantForAccessibility(MascotView.IMPORTANT_FOR_ACCESSIBILITY_NO);
        character.setVisibility(diaryReady ? MascotView.GONE : MascotView.VISIBLE);
        view.addView(character, new LinearLayout.LayoutParams(-1, dp(HEIGHT_DP)));
        Button caption = new Button(windowContext);
        caption.setText("오늘의 주행일기");
        caption.setAllCaps(false);
        caption.setTextColor(windowContext.getColor(R.color.panel_on_action));
        caption.setTextSize(16);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(dp(6), 0, dp(6), 0);
        GradientDrawable action = new GradientDrawable();
        action.setColor(windowContext.getColor(R.color.panel_action));
        action.setCornerRadius(dp(8));
        caption.setBackground(new RippleDrawable(ColorStateList.valueOf(windowContext.getColor(R.color.panel_ripple)),
                action, null));
        caption.setStateListAnimator(null);
        caption.setOnClickListener(v -> {
            if (parked && unrestricted && diaryAvailable() && Settings.canDrawOverlays(this)) {
                startActivity(openApp(true, false));
            }
        });
        caption.setVisibility(diaryReady ? Button.VISIBLE : Button.GONE);
        var buttonSize = new LinearLayout.LayoutParams(dp(200), dp(56));
        view.addView(caption, buttonSize);
        try {
            windows.addView(view, placement);
            companion = view;
            buddy = character;
            diaryPrompt = caption;
        } catch (WindowManager.BadTokenException | SecurityException e) {
            Log.w(TAG, "Overlay window unavailable", e);
        }
    }

    private Rect availableArea() {
        Rect area;
        if (Build.VERSION.SDK_INT >= 30) {
            var metrics = windows.getMaximumWindowMetrics();
            area = new Rect(metrics.getBounds());
            Insets bars = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            area.set(area.left + bars.left, area.top + bars.top, area.right - bars.right, area.bottom - bars.bottom);
        } else {
            Point size = new Point();
            windows.getDefaultDisplay().getRealSize(size);
            area = new Rect(0, dp(32), size.x, size.y - dp(48));
        }
        area.inset(dp(16), dp(16));
        return area;
    }

    private void place(int x, int y) {
        placement.x = Math.max(safeArea.left, Math.min(x, safeArea.right - placement.width));
        placement.y = Math.max(safeArea.top, Math.min(y, safeArea.bottom - placement.height));
        if (companion != null) windows.updateViewLayout(companion, placement);
    }

    private int dp(int value) {
        return Math.round(value * windowContext.getResources().getDisplayMetrics().density);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        removeWindow();
        updateWindow();
    }

    private void removeWindow() {
        if (companion == null) return;
        LinearLayout attached = companion;
        companion = null;
        buddy = null;
        diaryPrompt = null;
        try {
            windows.removeViewImmediate(attached);
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "Overlay window was already removed", e);
        }
    }

    @Override public void onDestroy() {
        if (running == this) running = null;
        preferences.unregisterOnSharedPreferenceChangeListener(changes);
        removeWindow();
        stopWatchingCar();
        if (navigation != null) navigation.close();
        if (car != null) car.disconnect();
        for (Runnable listener : tripListeners) listener.run();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private final class Drag implements android.view.View.OnTouchListener {
        private final int slop = ViewConfiguration.get(windowContext).getScaledTouchSlop();
        private float downX, downY;
        private int windowX, windowY;
        private boolean moving;

        @Override public boolean onTouch(android.view.View view, MotionEvent event) {
            if (!parked || !unrestricted || companion == null || diaryAvailable()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    downX = event.getRawX();
                    downY = event.getRawY();
                    windowX = placement.x;
                    windowY = placement.y;
                    moving = false;
                }
                case MotionEvent.ACTION_MOVE -> {
                    int dx = Math.round(event.getRawX() - downX);
                    int dy = Math.round(event.getRawY() - downY);
                    moving |= Math.abs(dx) > slop || Math.abs(dy) > slop;
                    if (moving) place(windowX + dx, windowY + dy);
                }
                case MotionEvent.ACTION_UP -> {
                    if (moving) {
                        place(windowX + Math.round(event.getRawX() - downX),
                                windowY + Math.round(event.getRawY() - downY));
                        preferences.edit().putInt(X, placement.x).putInt(Y, placement.y).apply();
                    } else {
                        view.performClick();
                    }
                }
                case MotionEvent.ACTION_CANCEL -> {
                    if (moving) place(windowX, windowY);
                }
            }
            return true;
        }
    }
}
