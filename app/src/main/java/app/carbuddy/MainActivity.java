package app.carbuddy;

import android.app.Activity;
import android.app.AlertDialog;
import android.car.Car;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.car.hardware.property.CarPropertyManager;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int PAPER = 0xfff7f2e8, INK = 0xff183731, MUTED = 0xff46615a;
    private static final int STAGE = 0xff173b35;
    private static final TripStory EMPTY = new TripStory(null, null, null);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Car car;
    private CarUxRestrictionsManager restrictions;
    private TripRecorder recorder;
    private VehicleReader reader;
    private TripStory trip = EMPTY;
    private boolean resumed, allowed, loading, open, musicPlaying;
    private AudioManager audio;
    private final AudioManager.AudioPlaybackCallback playback = new AudioManager.AudioPlaybackCallback() {
        @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            updateMusic();
        }
    };
    private CharacterArt character;
    private Expression preview;
    private AlertDialog reactionDialog;
    private int request;
    private TextView headerStatus, mascotTitle, mascotNote, distance, duration, battery, storyCopy;
    private Button diary, refresh, reactions, speedPermission;
    private LinearLayout characters;
    private final Button[] characterButtons = new Button[CharacterArt.values().length];
    private Mascot mascot;
    private LinearLayout metrics, controls;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        character = CharacterArt.saved(getPreferences(MODE_PRIVATE).getString("character", ""));
        audio = getSystemService(AudioManager.class);
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
        if (audio != null) {
            audio.registerAudioPlaybackCallback(playback, handler);
            updateMusic();
        }
        if (car != null && car.isConnected()) startRecorder(car);
        checkRestrictions();
    }

    @Override protected void onPause() {
        resumed = false;
        if (audio != null) audio.unregisterAudioPlaybackCallback(playback);
        musicPlaying = false;
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

    private void updateMusic() {
        boolean active = resumed && audio != null && audio.getActivePlaybackConfigurations().stream().anyMatch(config -> {
            AudioAttributes attributes = config.getAudioAttributes();
            return attributes.getUsage() == AudioAttributes.USAGE_MEDIA
                    && attributes.getContentType() == AudioAttributes.CONTENT_TYPE_MUSIC;
        });
        if (musicPlaying == active) return;
        musicPlaying = active;
        if (allowed) render();
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
            preview = null;
            if (reactionDialog != null) reactionDialog.dismiss();
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
        characters = column();
        stage.addView(characters, params(-1, -2, 4, 0));
        for (int start = 0; start < characterButtons.length; start += 2) {
            LinearLayout row = new LinearLayout(this);
            characters.addView(row, params(-1, -2, 0, 6));
            for (int index = start; index < start + 2; index++) {
                CharacterArt option = CharacterArt.values()[index];
                Button choice = button(option.label, STAGE, Color.WHITE, 0xff73968a);
                choice.setPadding(dp(6), 0, dp(6), 0);
                choice.setSingleLine(true);
                LinearLayout.LayoutParams choiceSize = new LinearLayout.LayoutParams(0, dp(48), 1f);
                if (index == start) choiceSize.setMarginEnd(dp(8));
                row.addView(choice, choiceSize);
                characterButtons[index] = choice;
                choice.setOnClickListener(v -> {
                    if (!checkRestrictions()) return;
                    character = option;
                    getPreferences(MODE_PRIVATE).edit().putString("character", option.name()).apply();
                    render();
                });
            }
        }
        reactions = button("표정 미리보기", STAGE, 0xffd4ebe1, 0xff73968a);
        reactions.setOnClickListener(v -> chooseExpression());
        stage.addView(reactions, params(-1, dp(48), 3, 0));

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
        TripMeter.Summary measured = lastMeasured();
        Expression face = preview != null ? preview : automaticExpression(measured);
        headerStatus.setText(allowed ? "주차 중  ·  Pleos Vehicle" : "주차 시 이용 가능");
        mascotTitle.setText(!allowed ? "안전하게 도착하면 만나!" : character.label);
        mascotNote.setText(!allowed ? "주차한 뒤 다시 열어 줘."
                : (preview != null ? "미리보기 · " : "") + face.note);
        mascot.setVisibility(allowed ? View.VISIBLE : View.GONE);
        if (allowed) mascot.show(character, face);
        characters.setVisibility(allowed ? View.VISIBLE : View.GONE);
        reactions.setVisibility(allowed ? View.VISIBLE : View.GONE);
        for (Button choice : characterButtons) {
            boolean selected = choice.getText().toString().equals(character.label);
            choice.setSelected(selected);
            choice.setTextColor(selected ? INK : Color.WHITE);
            choice.setBackgroundTintList(selected ? ColorStateList.valueOf(0xffc9e6d6) : null);
        }
        metrics.setVisibility(allowed ? View.VISIBLE : View.GONE);
        controls.setVisibility(allowed ? View.VISIBLE : View.GONE);
        storyCopy.setText(!allowed ? "차량의 주행 제한이 해제되면 일기를 볼 수 있어요."
                : loading ? "차량에서 이번 트립 기록을 읽고 있어요."
                : !entry.hasData() ? "아직 확인할 수 있는 차량 기록이 없어요."
                : open ? entry.text() + motionNote(measured) : character.label + "에게 이번 이동 이야기를 들어보세요.");
        distance.setText(entry.distance());
        duration.setText(entry.duration());
        battery.setText(trip.battery() == null ? "—" : trip.battery() + "%");
        diary.setText(open ? "일기 닫기" : "일기 열기");
        diary.setEnabled(allowed && !loading && entry.hasData());
        diary.setAlpha(diary.isEnabled() ? 1f : .55f);
        refresh.setEnabled(allowed && !loading);
        speedPermission.setVisibility(allowed && !hasSpeedPermission() ? View.VISIBLE : View.GONE);
    }

    private TripMeter.Summary lastMeasured() {
        return recorder == null ? null : recorder.last();
    }

    private Expression automaticExpression(TripMeter.Summary measured) {
        if (trip.low()) return Expression.BATTERY;
        if (musicPlaying) return Expression.MUSIC;
        if (measured != null && measured.hardBraking()) return Expression.BRAKE;
        if (measured != null && measured.speedExceeded()) return Expression.SPEED;
        return Expression.NEUTRAL;
    }

    private String motionNote(TripMeter.Summary measured) {
        if (measured == null) return "";
        String note = measured.hardBraking() ? "\n급감속이 있었어." : "";
        if (measured.speedExceeded()) {
            note += "\n미리 정한 " + TripMeter.SPEED_CUE_KPH + " km/h를 넘은 구간이 있었어.";
        }
        return note;
    }

    private void chooseExpression() {
        if (!checkRestrictions()) return;
        Expression[] choices = Expression.values();
        String[] labels = new String[choices.length];
        labels[0] = "자동 · 차량 상태 반영";
        for (int i = 1; i < choices.length; i++) labels[i] = choices[i].label;
        reactionDialog = new AlertDialog.Builder(this).setTitle(character.label + " 표정 미리보기")
                .setSingleChoiceItems(labels, preview == null ? 0 : preview.ordinal(), (dialog, index) -> {
                    if (checkRestrictions()) {
                        preview = index == 0 ? null : choices[index];
                        render();
                    }
                    dialog.dismiss();
                }).setNegativeButton("닫기", null).create();
        reactionDialog.setOnDismissListener(dialog -> reactionDialog = null);
        reactionDialog.show();
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

    private enum Expression {
        NEUTRAL("", "지금 차의 상태를 함께 살펴봐요."),
        BATTERY("배터리 부족", "에너지가 적어서 땀이 나요."),
        MUSIC("음악 재생", "음악이 들리니 신나요."),
        BRAKE("급감속", "갑자기 속도가 줄어 깜짝 놀랐어요."),
        SPEED("속도 초과", "정해 둔 속도를 넘어서 긴장했어요.");

        final String label;
        final String note;

        Expression(String label, String note) {
            this.label = label;
            this.note = note;
        }
    }

    private enum CharacterArt {
        MOMOKA("모모카", R.drawable.momoka),
        DINOCA("디노카", R.drawable.dinoca),
        NYANGCA("냥카", R.drawable.nyangca),
        POCA("포카", R.drawable.poca);

        final String label;
        final int image;

        CharacterArt(String label, int image) {
            this.label = label;
            this.image = image;
        }

        static CharacterArt saved(String name) {
            for (CharacterArt choice : values()) {
                if (choice.name().equals(name)) return choice;
            }
            return MOMOKA;
        }
    }

    private final class Mascot extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Rect source = new Rect();
        private final RectF destination = new RectF();
        private CharacterArt current;
        private Expression face;
        private Bitmap sheet;

        Mascot() { super(MainActivity.this); }

        void show(CharacterArt next, Expression expression) {
            if (current == next && face == expression) return;
            if (current != next) {
                sheet = BitmapFactory.decodeResource(getResources(), next.image);
                current = next;
            }
            face = expression;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (sheet == null) return;
            int cellWidth = sheet.getWidth() / 2;
            int cellHeight = sheet.getHeight() / 3;
            int left = face.ordinal() % 2 * cellWidth;
            int top = face.ordinal() / 2 * cellHeight;
            source.set(left, top, left + cellWidth, top + cellHeight);
            float scale = Math.min(getWidth() / (float) cellWidth, getHeight() / (float) cellHeight);
            float width = cellWidth * scale, height = cellHeight * scale;
            float x = (getWidth() - width) / 2, y = (getHeight() - height) / 2;
            destination.set(x, y, x + width, y + height);
            canvas.drawBitmap(sheet, source, destination, paint);
        }
    }
}
