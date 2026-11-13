package app.carbuddy;

import android.app.Activity;
import android.car.Car;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.car.hardware.property.CarPropertyManager;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int PAPER = 0xfff7f2e8, INK = 0xff183731, MUTED = 0xff46615a;
    private static final int MINT = 0xff95ddb1, PEACH = 0xffffb793, STAGE = 0xff173b35;
    private static final TripStory EMPTY = new TripStory(null, null, null);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Car car;
    private CarUxRestrictionsManager restrictions;
    private TripRecorder recorder;
    private VehicleReader reader;
    private TripStory trip = EMPTY;
    private boolean resumed, allowed, loading, open, peach;
    private int request;
    private TextView headerStatus, mascotTitle, mascotNote, distance, duration, battery, storyCopy;
    private Button diary, refresh, color, speedPermission;
    private Mascot mascot;
    private LinearLayout metrics, controls;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        peach = getPreferences(MODE_PRIVATE).getBoolean("peach", false);
        build();
        car = Car.createCar(this, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, (connected, ready) -> {
            closeRecorder();
            restrictions = null;
            if (ready) {
                try {
                    var manager = (CarUxRestrictionsManager) connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
                    if (manager != null) {
                        manager.registerListener(value -> runOnUiThread(() -> {
                            if (restrictions == manager) updateRestrictions(value);
                        }));
                        restrictions = manager;
                    }
                } catch (RuntimeException e) {
                    android.util.Log.w("CarBuddy", "Driving restrictions unavailable", e);
                }
            }
            if (ready) startRecorder(connected);
            checkRestrictions();
        });
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (car != null && car.isConnected()) startRecorder(car);
        checkRestrictions();
    }

    @Override protected void onPause() {
        resumed = false;
        updateRestrictions(null);
        super.onPause();
    }

    @Override protected void onDestroy() {
        closeRecorder();
        if (restrictions != null) restrictions.unregisterListener();
        restrictions = null;
        if (car != null) car.disconnect();
        super.onDestroy();
    }

    private boolean hasSpeedPermission() {
        return checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED;
    }

    private void startRecorder(Car connected) {
        if (recorder != null || !hasSpeedPermission()) return;
        try {
            var manager = (CarPropertyManager) connected.getCarManager(Car.PROPERTY_SERVICE);
            if (manager != null) {
                recorder = new TripRecorder(manager, this::render);
                recorder.start();
            }
        } catch (RuntimeException e) {
            android.util.Log.w("CarBuddy", "Trip recorder setup failed", e);
        }
    }

    private void closeRecorder() {
        if (recorder == null) return;
        recorder.close();
        recorder = null;
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 1 && car != null && car.isConnected()) startRecorder(car);
        render();
    }

    private TripStory diaryTrip() {
        return trip.withMeasured(recorder == null ? null : recorder.last());
    }

    private boolean checkRestrictions() {
        CarUxRestrictions value = null;
        try {
            if (resumed && restrictions != null) value = restrictions.getCurrentCarUxRestrictions();
        } catch (RuntimeException e) {
            android.util.Log.w("CarBuddy", "Cannot read driving restrictions", e);
        }
        updateRestrictions(value);
        return allowed;
    }

    private void updateRestrictions(CarUxRestrictions value) {
        boolean next = resumed && value != null && !value.isRequiresDistractionOptimization();
        if (allowed == next) { render(); return; }
        allowed = next;
        if (!allowed) {
            request++;
            loading = open = false;
            trip = EMPTY;
            if (reader != null) { reader.close(); reader = null; }
        } else {
            try {
                reader = new VehicleReader(this, handler);
                reader.watchBattery(level -> {
                    if (!allowed || loading) return;
                    trip = new TripStory(level, trip.km(), trip.seconds());
                    render();
                });
            } catch (RuntimeException e) {
                android.util.Log.w("CarBuddy", "Vehicle SDK initialization failed", e);
            }
            load();
        }
        render();
    }

    private void load() {
        if (reader == null || !allowed) return;
        int token = ++request;
        loading = true;
        open = false;
        trip = EMPTY;
        render();
        reader.read(value -> {
            if (token != request || !allowed) return;
            trip = value;
            loading = false;
            render();
        });
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAPER);
        LinearLayout outer = column();
        int gutter = getResources().getConfiguration().screenWidthDp >= 900 ? 42 : 22;
        outer.setPadding(dp(gutter), dp(22), dp(gutter), dp(26));
        scroll.addView(outer);
        setContentView(scroll);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        outer.addView(header, params(-1, -2, 0, 22));
        TextView brand = text("카버디", 25, INK, true);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        headerStatus = text("", 14, MUTED, true);
        headerStatus.setGravity(Gravity.END);
        header.addView(headerStatus);

        boolean wide = getResources().getConfiguration().screenWidthDp >= 720
                && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        outer.addView(content, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout stage = column();
        stage.setBackground(round(STAGE, 22));
        stage.setPadding(dp(26), dp(24), dp(26), dp(22));
        LinearLayout.LayoutParams stageParams = wide
                ? new LinearLayout.LayoutParams(0, -2, 1.04f) : params(-1, -2, 0, 24);
        if (wide) stageParams.rightMargin = dp(24);
        content.addView(stage, stageParams);
        mascotTitle = text("", 26, Color.WHITE, true);
        stage.addView(mascotTitle);
        mascotNote = text("", 15, 0xffc2e0d5, false);
        stage.addView(mascotNote, params(-1, -2, 6, 0));
        mascot = new Mascot();
        mascot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        stage.addView(mascot, new LinearLayout.LayoutParams(-1, dp(wide ? 218 : 170)));
        color = button("차 색상 바꾸기", STAGE, 0xffd4ebe1, 0xff73968a);
        color.setOnClickListener(v -> {
            if (!checkRestrictions()) return;
            peach = !peach;
            getPreferences(MODE_PRIVATE).edit().putBoolean("peach", peach).apply();
            render();
        });
        stage.addView(color, new LinearLayout.LayoutParams(-2, dp(52)));

        LinearLayout panel = column();
        panel.setPadding(dp(4), dp(wide ? 8 : 0), dp(4), 0);
        content.addView(panel, wide ? new LinearLayout.LayoutParams(0, -2, 1f) : params(-1, -2, 0, 0));
        panel.addView(text("이번 주행 일기", 33, INK, true));
        storyCopy = text("", 19, MUTED, false);
        storyCopy.setLineSpacing(dp(4), 1f);
        storyCopy.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(storyCopy, params(-1, -2, 12, 22));
        metrics = new LinearLayout(this);
        metrics.setBackground(round(0xffe9e9dc, 16));
        metrics.setPadding(dp(15), dp(13), dp(12), dp(13));
        distance = metric("주행 거리");
        duration = metric("주행 시간");
        battery = metric("남은 배터리");
        panel.addView(metrics, params(-1, -2, 0, 22));
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        diary = button("일기 열기", INK, Color.WHITE, 0);
        diary.setOnClickListener(v -> {
            if (checkRestrictions() && !loading && diaryTrip().hasData()) {
                open = !open;
                render();
            }
        });
        controls.addView(diary, params(-1, dp(56), 0, 8));
        refresh = button("차량 기록 다시 읽기", PAPER, INK, 0xff748a7e);
        refresh.setOnClickListener(v -> { if (checkRestrictions()) load(); });
        controls.addView(refresh, params(-1, dp(52), 0, 0));
        speedPermission = button("주행 기록 권한 허용", PAPER, INK, 0xff748a7e);
        speedPermission.setOnClickListener(v -> {
            if (checkRestrictions()) requestPermissions(new String[]{Car.PERMISSION_SPEED}, 1);
        });
        controls.addView(speedPermission, params(-1, dp(52), 8, 0));
        panel.addView(controls);
        render();
    }

    private void render() {
        TripStory entry = diaryTrip();
        headerStatus.setText(allowed ? "주차 중  ·  Pleos Vehicle" : "주차 시 이용 가능");
        mascotTitle.setText(!allowed ? "안전하게 도착하면 만나!" : trip.low() ? "에너지가 조금 부족해" : "안녕, 난 카버디야");
        mascotNote.setText(!allowed ? "주차한 뒤 다시 열어 줘." : trip.low() ? "다음 출발 전에 충전을 확인해 줘." : "지금 차의 상태를 함께 살펴봐요.");
        mascot.setVisibility(allowed ? View.VISIBLE : View.GONE);
        mascot.invalidate();
        color.setVisibility(allowed ? View.VISIBLE : View.GONE);
        metrics.setVisibility(allowed ? View.VISIBLE : View.GONE);
        controls.setVisibility(allowed ? View.VISIBLE : View.GONE);
        storyCopy.setText(!allowed ? "차량의 주행 제한이 해제되면 일기를 볼 수 있어요."
                : loading ? "차량에서 이번 트립 기록을 읽고 있어요."
                : !entry.hasData() ? "아직 확인할 수 있는 차량 기록이 없어요."
                : open ? entry.text() : "카버디에게 이번 이동 이야기를 들어보세요.");
        distance.setText(entry.distance());
        duration.setText(entry.duration());
        battery.setText(trip.battery() == null ? "—" : trip.battery() + "%");
        diary.setText(open ? "일기 닫기" : "일기 열기");
        diary.setEnabled(allowed && !loading && entry.hasData());
        diary.setAlpha(diary.isEnabled() ? 1f : .55f);
        refresh.setEnabled(allowed && !loading);
        speedPermission.setVisibility(allowed && !hasSpeedPermission() ? View.VISIBLE : View.GONE);
    }

    private TextView metric(String label) {
        LinearLayout box = column();
        box.setPadding(0, 0, dp(5), 0);
        box.addView(text(label, 13, MUTED, false));
        TextView value = text("—", 20, INK, true);
        box.addView(value, params(-1, -2, 5, 0));
        metrics.addView(box, new LinearLayout.LayoutParams(0, -2, 1));
        return value;
    }

    private int dp(float size) { return Math.round(size * getResources().getDisplayMetrics().density); }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout.LayoutParams params(int width, int height, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.topMargin = dp(top);
        p.bottomMargin = dp(bottom);
        return p;
    }
    private TextView text(String label, int size, int ink, boolean bold) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(ink);
        if (bold) view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return view;
    }
    private GradientDrawable round(int fill, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        return d;
    }
    private Button button(String label, int fill, int ink, int outline) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(ink);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setStateListAnimator(null);
        GradientDrawable shape = round(fill, 14);
        if (outline != 0) shape.setStroke(dp(1), outline);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x447ba992), shape, null));
        return button;
    }

    private final class Mascot extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Mascot() { super(MainActivity.this); }
        private void fill(int color) { paint.setColor(color); paint.setStyle(Paint.Style.FILL); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float scale = Math.min(getWidth() / 380f, getHeight() / 224f);
            c.save();
            c.translate((getWidth() - 380 * scale) / 2, (getHeight() - 224 * scale) / 2);
            c.scale(scale, scale);
            fill(0xff265348);
            c.drawOval(26, 165, 354, 193, paint);
            fill(0xff0c2825);
            c.drawRoundRect(68, 143, 116, 182, 11, 11, paint);
            c.drawRoundRect(264, 143, 312, 182, 11, 11, paint);
            fill(peach ? PEACH : MINT);
            path.reset();
            path.moveTo(48, 132); path.quadTo(48, 110, 75, 101);
            path.lineTo(99, 60); path.quadTo(108, 45, 130, 45);
            path.lineTo(250, 45); path.quadTo(271, 45, 281, 61);
            path.lineTo(306, 102); path.quadTo(333, 109, 333, 133);
            path.lineTo(333, 151); path.quadTo(333, 168, 313, 169);
            path.lineTo(69, 169); path.quadTo(48, 168, 48, 151); path.close();
            c.drawPath(path, paint);
            fill(0xff477467);
            path.reset();
            path.moveTo(102, 97); path.lineTo(117, 69); path.quadTo(122, 62, 134, 62);
            path.lineTo(246, 62); path.quadTo(258, 62, 263, 69);
            path.lineTo(279, 97); path.close(); c.drawPath(path, paint);
            fill(0xffeef8e4);
            c.drawRoundRect(62, 131, 98, 146, 7, 7, paint);
            c.drawRoundRect(283, 131, 319, 146, 7, 7, paint);
            fill(INK);
            c.drawOval(126, 115, 148, 140, paint);
            c.drawOval(233, 115, 255, 140, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(5);
            path.reset();
            if (trip.low()) {
                path.moveTo(177, 153); path.quadTo(190, 140, 203, 153);
                path.moveTo(126, 108); path.lineTo(146, 102);
                path.moveTo(235, 102); path.lineTo(255, 108);
            } else {
                path.moveTo(176, 143); path.quadTo(190, 160, 204, 143);
            }
            c.drawPath(path, paint);
            c.restore();
        }
    }
}
