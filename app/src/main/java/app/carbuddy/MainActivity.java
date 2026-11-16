package app.carbuddy;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.car.Car;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.car.hardware.property.CarPropertyManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.Objects;

public final class MainActivity extends Activity {
    private int surface, ink, muted, line, selected, primary, onPrimary, focus, ripple;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Car car;
    private CarUxRestrictionsManager restrictions;
    private final Runnable tripListener = this::render;
    private VehicleReader reader;
    private Integer fuelLevel;
    private boolean resumed, allowed, loading, musicPlaying;
    private AudioManager audio;
    private final AudioManager.AudioPlaybackCallback playback = new AudioManager.AudioPlaybackCallback() {
        @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            updateMusic();
        }
    };
    private CharacterArt character;
    private CharacterArt pickerChoice, previousPickerChoice;
    private ValueAnimator pickerMotion;
    private SharedPreferences preferences;
    private boolean choosingCharacter, settingsOpen;
    private boolean pendingOpenDiary;
    private String floatingMessage = "";
    private Dialog diaryDialog;
    private int request;
    private TextView parkedOnly, floatingHint, recordStatus, diaryFuel, diaryCopy;
    private Button finish, arrivalDiary, previousDiary, fuelPermission, speedPermission, floatingToggle, closePicker;
    private Button confirmPicker;
    private LinearLayout dashboard, picker, settings;
    private final Button[] characterButtons = new Button[CharacterArt.values().length];
    private final BitmapDrawable[][] pickerFaces = new BitmapDrawable[characterButtons.length][Expression.values().length];
    private MascotView mascot;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        surface = getColor(R.color.panel_surface);
        ink = getColor(R.color.panel_ink);
        muted = getColor(R.color.panel_muted);
        line = getColor(R.color.panel_line);
        selected = getColor(R.color.panel_selected);
        primary = getColor(R.color.panel_action);
        onPrimary = getColor(R.color.panel_on_action);
        focus = getColor(R.color.panel_focus);
        ripple = getColor(R.color.panel_ripple);
        preferences = CharacterArt.preferences(this);
        character = CharacterArt.saved(preferences.getString(CharacterArt.CHOICE, ""));
        choosingCharacter = !hasChosenCharacter() || (state != null && state.getBoolean("picker"));
        if (state != null && state.getString("picker_choice") != null) {
            pickerChoice = CharacterArt.saved(state.getString("picker_choice"));
        }
        if (state != null && state.getString("picker_previous") != null) {
            previousPickerChoice = CharacterArt.saved(state.getString("picker_previous"));
        }
        pendingOpenDiary = state == null ? getIntent().getBooleanExtra(FloatingBuddyService.OPEN_DIARY, false)
                : state.getBoolean("pending_diary");
        settingsOpen = state == null ? !choosingCharacter && !pendingOpenDiary : state.getBoolean("settings");
        audio = getSystemService(AudioManager.class);
        build();
        car = Car.createCar(this, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, (connected, ready) -> {
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
            checkRestrictions();
        });
    }

    @Override protected void onStart() {
        super.onStart();
        FloatingBuddyService.addTripListener(tripListener);
        FloatingBuddyService.activityVisible(true);
    }

    @Override protected void onStop() {
        FloatingBuddyService.removeTripListener(tripListener);
        FloatingBuddyService.activityVisible(false);
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (audio != null) {
            audio.registerAudioPlaybackCallback(playback, handler);
            updateMusic();
        }
        checkRestrictions();
        syncFloatingBuddy();
        render();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("picker", choosingCharacter);
        if (pickerChoice != null) state.putString("picker_choice", pickerChoice.name());
        if (previousPickerChoice != null) state.putString("picker_previous", previousPickerChoice.name());
        state.putBoolean("settings", settingsOpen);
        state.putBoolean("pending_diary", pendingOpenDiary || diaryDialog != null);
        super.onSaveInstanceState(state);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        pendingOpenDiary |= intent.getBooleanExtra(FloatingBuddyService.OPEN_DIARY, false);
        if (intent.getBooleanExtra(FloatingBuddyService.OPEN_SETTINGS, false)) {
            setCharacterPickerOpen(false);
            settingsOpen = true;
        }
        render();
    }

    @Override public void onBackPressed() {
        if (choosingCharacter && hasChosenCharacter()) {
            setCharacterPickerOpen(false);
            render();
        } else if (settingsOpen) {
            closeSettings();
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onPause() {
        resumed = false;
        if (audio != null) audio.unregisterAudioPlaybackCallback(playback);
        musicPlaying = false;
        updateRestrictions(null);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (restrictions != null) restrictions.unregisterListener();
        restrictions = null;
        if (car != null) car.disconnect();
        super.onDestroy();
    }

    private boolean hasSpeedPermission() {
        return checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasFuelPermission() {
        return checkSelfPermission(Car.PERMISSION_ENERGY) == PackageManager.PERMISSION_GRANTED;
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

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 1 && resumed) syncFloatingBuddy();
        if (code == 2 && allowed) startFuel();
        render();
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == 3 && floatingEnabled() && syncFloatingBuddy()) finish();
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
            if (pickerMotion != null) pickerMotion.end();
            request++;
            loading = false;
            if (diaryDialog != null) diaryDialog.dismiss();
            fuelLevel = null;
            if (reader != null) { reader.close(); reader = null; }
        } else {
            startFuel();
        }
        render();
    }

    private void startFuel() {
        if (reader != null || car == null || !car.isConnected() || !hasFuelPermission()) return;
        try {
            var manager = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
            if (manager == null) return;
            reader = new VehicleReader(manager, handler, level -> {
                if (!allowed || loading || Objects.equals(fuelLevel, level)) return;
                fuelLevel = level;
                render();
            });
            reader.watchFuel();
            load();
        } catch (RuntimeException e) {
            android.util.Log.w("CarBuddy", "Fuel reader setup failed", e);
        }
    }

    private void load() {
        if (reader == null || !allowed) return;
        int token = ++request;
        loading = true;
        render();
        reader.readFuel(value -> {
            if (token != request || !allowed) return;
            fuelLevel = value;
            loading = false;
            render();
        });
    }

    private boolean hasChosenCharacter() {
        return preferences.getBoolean(CharacterArt.PICKED, false);
    }

    private boolean floatingEnabled() {
        return preferences.getBoolean(CharacterArt.FLOATING, false) && Settings.canDrawOverlays(this);
    }

    private void chooseCharacter(CharacterArt choice) {
        if (choice == null || !checkRestrictions() || !picker.isShown()) return;
        boolean first = !hasChosenCharacter();
        character = choice;
        setCharacterPickerOpen(false);
        settingsOpen = false;
        SharedPreferences.Editor edits = preferences.edit().putString(CharacterArt.CHOICE, choice.name())
                .putBoolean(CharacterArt.PICKED, true);
        if (first) edits.putBoolean(CharacterArt.FLOATING, true);
        edits.apply();
        if (first && !Settings.canDrawOverlays(this)) openOverlaySettings();
        if (syncFloatingBuddy() && floatingEnabled()) finish();
        else {
            settingsOpen = true;
            render();
        }
    }

    private void setCharacterPickerOpen(boolean open) {
        if (pickerMotion != null) pickerMotion.end();
        pickerChoice = previousPickerChoice = null;
        choosingCharacter = open;
    }

    private void previewCharacter(CharacterArt choice) {
        if (!checkRestrictions() || !picker.isShown()) return;
        if (pickerMotion != null) pickerMotion.end();
        CharacterArt previous = pickerSelection();
        if (previous != choice) previousPickerChoice = previous;
        pickerChoice = choice;
        render();
        if (!ValueAnimator.areAnimatorsEnabled()) return;

        Button card = characterButtons[choice.ordinal()];
        BitmapDrawable face = pickerFaces[choice.ordinal()][Expression.MUSIC.ordinal()];
        int width = face.getBounds().width();
        int height = face.getBounds().height();
        pickerMotion = ValueAnimator.ofFloat(0f, -1f, 1f, -.7f, .7f, -.35f, .35f, 0f);
        pickerMotion.setDuration(420);
        pickerMotion.addUpdateListener(animation -> {
            float rev = (float) animation.getAnimatedValue();
            int x = dp(5 * rev);
            int y = dp(-3 * Math.abs(rev));
            face.setBounds(x, y, x + width, y + height);
            card.invalidate();
        });
        pickerMotion.start();
    }

    private void toggleFloatingBuddy() {
        if (!checkRestrictions()) return;
        boolean enable = !floatingEnabled();
        preferences.edit().putBoolean(CharacterArt.FLOATING, enable).apply();
        floatingMessage = "";
        if (enable && !Settings.canDrawOverlays(this)) openOverlaySettings();
        if (syncFloatingBuddy() && floatingEnabled()) finish();
        else render();
    }

    private void openOverlaySettings() {
        try {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), 3);
        } catch (ActivityNotFoundException | SecurityException e) {
            floatingMessage = "이 기기에서는 다른 앱 위에 표시하는 권한을 설정할 수 없어요.";
        }
    }

    private boolean syncFloatingBuddy() {
        Intent service = new Intent(this, FloatingBuddyService.class);
        if (!hasChosenCharacter()) return false;
        try {
            startForegroundService(service);
            if (floatingEnabled()) floatingMessage = "";
            return true;
        } catch (RuntimeException e) {
            android.util.Log.w("CarBuddy", "Cannot start floating companion", e);
            floatingMessage = "캐릭터를 띄우지 못했어요. 주차 중 다시 시도해 주세요.";
            render();
            return false;
        }
    }

    private void closeSettings() {
        if (floatingEnabled()) finish();
        else {
            settingsOpen = false;
            render();
        }
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        Configuration config = getResources().getConfiguration();
        int gutter = config.screenWidthDp >= 780 ? 40 : 20;
        int stageWidth = Math.min(392, Math.max(1, config.screenWidthDp - gutter * 2));
        LinearLayout outer = column();
        outer.setPadding(dp(gutter), dp(24), dp(gutter), dp(32));
        scroll.addView(outer);
        setContentView(scroll);
        dashboard = column();
        outer.addView(dashboard);

        parkedOnly = text("주차한 뒤 카버디를 이용할 수 있어요.", 22, muted, false);
        parkedOnly.setBackground(buttonShape(surface, line, 1));
        parkedOnly.setPadding(dp(16), dp(10), dp(16), dp(10));
        dashboard.addView(parkedOnly, params(-2, -2, 12, 0));

        mascot = new MascotView(this);
        mascot.setForeground(buttonBackground(Color.TRANSPARENT, 0, false));
        mascot.setFocusable(true);
        mascot.setOnClickListener(v -> {
            if (checkRestrictions()) {
                if (settingsOpen) closeSettings();
                else {
                    settingsOpen = true;
                    render();
                }
            }
        });
        dashboard.addView(mascot, params(dp(stageWidth), dp(Math.min(280, stageWidth * .74f)), 0, 0));

        arrivalDiary = button("오늘의 주행일기", primary, onPrimary, 0);
        arrivalDiary.setOnClickListener(v -> openCurrentDiary());
        dashboard.addView(arrivalDiary, params(dp(stageWidth), -2, 12, 0));

        settings = column();
        settings.setPadding(dp(14), dp(14), dp(14), dp(14));
        settings.setBackground(buttonShape(surface, line, 1));
        dashboard.addView(settings, params(dp(stageWidth), -2, 16, 0));
        Button changeCharacter = button("캐릭터 선택하기", surface, ink, line);
        changeCharacter.setOnClickListener(v -> {
            if (checkRestrictions()) {
                setCharacterPickerOpen(true);
                render();
            }
        });
        settings.addView(changeCharacter, params(-1, -2, 0, 8));
        floatingToggle = button("다른 앱 위에 표시", surface, ink, line);
        floatingToggle.setOnClickListener(v -> toggleFloatingBuddy());
        settings.addView(floatingToggle, params(-1, -2, 0, 0));
        floatingHint = text("", 17, muted, false);
        settings.addView(floatingHint, params(-1, -2, 8, 0));
        settings.addView(divider(), params(-1, dp(1), 16, 16));
        recordStatus = text("", 18, muted, false);
        settings.addView(recordStatus, params(-1, -2, 0, 10));
        finish = button("주행 끝내기", surface, ink, line);
        finish.setOnClickListener(v -> {
            TripRecorder recorder = FloatingBuddyService.trip();
            if (checkRestrictions() && recorder != null && recorder.finish()) {
                render();
                openCurrentDiary();
            }
        });
        settings.addView(finish, params(-1, -2, 0, 8));
        previousDiary = button("최근 주행일기", surface, ink, line);
        previousDiary.setOnClickListener(v -> openCurrentDiary());
        settings.addView(previousDiary, params(-1, -2, 0, 8));
        fuelPermission = button("연료 정보 권한 허용", surface, ink, line);
        fuelPermission.setOnClickListener(v -> {
            if (checkRestrictions() && !hasFuelPermission()) {
                requestPermissions(new String[]{Car.PERMISSION_ENERGY}, 2);
            }
        });
        settings.addView(fuelPermission, params(-1, -2, 0, 0));
        speedPermission = button("주행 기록 권한 허용", surface, ink, line);
        speedPermission.setOnClickListener(v -> {
            if (checkRestrictions()) requestPermissions(new String[]{Car.PERMISSION_SPEED}, 1);
        });
        settings.addView(speedPermission, params(-1, -2, 8, 0));
        buildPicker(outer, config.screenWidthDp - gutter * 2);
        render();
    }

    private void buildPicker(LinearLayout outer, int width) {
        picker = column();
        boolean wide = width >= 780;
        int inset = wide ? 40 : 20;
        int gap = wide ? 72 : 16;
        int cardWidth = Math.min(360, (width - inset * 2 - gap) / 2);
        picker.setBackground(buttonShape(surface, line, 1));
        picker.setPadding(dp(inset), dp(32), dp(inset), dp(28));
        outer.addView(picker, params(-1, -2, 8, 0));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        picker.addView(header, params(-1, -2, 0, 12));
        header.addView(text("카버디", 38, ink, true), new LinearLayout.LayoutParams(0, -2, 1f));
        closePicker = button("닫기", selected, ink, 0);
        closePicker.setOnClickListener(v -> {
            setCharacterPickerOpen(false);
            render();
        });
        header.addView(closePicker, params(-2, -2, 0, 0));
        picker.addView(divider(), params(-1, dp(1), 0, 16));
        TextView heading = text("함께할 캐릭터를 골라 주세요", wide ? 36 : 28, ink, true);
        heading.setAccessibilityHeading(true);
        picker.addView(heading, params(-1, -2, 0, wide ? 48 : 24));

        int columns = 2;
        int artHeight = Math.max(1, (cardWidth - 16) * 2 / 3);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 2;
        Expression[] poses = {Expression.NEUTRAL, Expression.MUSIC, Expression.FUEL_LOW};
        CharacterArt[] choices = CharacterArt.values();
        for (int start = 0; start < choices.length; start += columns) {
            LinearLayout row = new LinearLayout(this);
            LinearLayout.LayoutParams rowSize = params(-2, -2, start == 0 ? 0 : wide ? 24 : 16, 0);
            rowSize.gravity = Gravity.CENTER_HORIZONTAL;
            picker.addView(row, rowSize);
            for (int index = start; index < Math.min(start + columns, choices.length); index++) {
                CharacterArt option = choices[index];
                Button choice = button(option.label, surface, ink, line);
                choice.setTextSize(wide ? 22 : 20);
                choice.setPadding(dp(8), dp(18), dp(8), dp(18));
                Bitmap atlas = BitmapFactory.decodeResource(getResources(), option.image, options);
                for (Expression pose : poses) {
                    pickerFaces[index][pose.ordinal()] = thumbnail(atlas, pose, artHeight);
                }
                choice.setCompoundDrawables(null, pickerFaces[index][Expression.NEUTRAL.ordinal()], null, null);
                choice.setCompoundDrawablePadding(dp(8));
                choice.setBackground(buttonBackground(surface, line, true));
                LinearLayout.LayoutParams size = params(dp(cardWidth), -2, 0, 0);
                if (index > start) size.setMarginStart(dp(gap));
                row.addView(choice, size);
                characterButtons[index] = choice;
                choice.setOnClickListener(v -> previewCharacter(option));
            }
        }

        confirmPicker = button("선택하기", primary, onPrimary, 0);
        confirmPicker.setMinWidth(dp(116));
        confirmPicker.setOnClickListener(v -> chooseCharacter(pickerSelection()));
        LinearLayout.LayoutParams confirmSize = params(-2, -2, 20, 0);
        confirmSize.gravity = Gravity.END;
        picker.addView(confirmPicker, confirmSize);
    }

    private CharacterArt pickerSelection() {
        return pickerChoice == null && hasChosenCharacter() ? character : pickerChoice;
    }

    private void renderPicker() {
        CharacterArt pending = pickerSelection();
        for (CharacterArt option : CharacterArt.values()) {
            Button card = characterButtons[option.ordinal()];
            card.setSelected(option == pending);
            Expression pose = option == pickerChoice ? Expression.MUSIC
                    : option == previousPickerChoice ? Expression.FUEL_LOW : Expression.NEUTRAL;
            BitmapDrawable face = pickerFaces[option.ordinal()][pose.ordinal()];
            if (card.getCompoundDrawables()[1] != face) card.setCompoundDrawables(null, face, null, null);
            card.setContentDescription(option == previousPickerChoice ? option.label + ", 시무룩한 표정" : null);
        }
        boolean canConfirm = pending != null;
        if (confirmPicker.isEnabled() != canConfirm) {
            confirmPicker.setEnabled(canConfirm);
            confirmPicker.setBackground(buttonBackground(canConfirm ? primary : selected, 0, false));
        }
    }

    private void render() {
        TripRecorder recorder = FloatingBuddyService.trip();
        boolean parked = recorder != null && recorder.parked();
        boolean active = recorder != null && recorder.active();
        boolean incomplete = recorder != null && recorder.incomplete();
        TripMeter.Summary measured = parked ? recorder.summary() : null;
        boolean diaryAvailable = allowed && parked && !active && measured != null;
        boolean arrival = diaryAvailable && recorder.diaryReady();
        boolean launchDiary = pendingOpenDiary && allowed && parked;
        if (launchDiary) pendingOpenDiary = false;
        boolean diaryInFront = diaryDialog != null || (launchDiary && diaryAvailable);
        boolean showPicker = allowed && choosingCharacter && !arrival && !diaryInFront;
        boolean showMascot = allowed && !showPicker && !arrival && !diaryInFront;
        closePicker.setVisibility(hasChosenCharacter() ? View.VISIBLE : View.GONE);
        picker.setVisibility(showPicker ? View.VISIBLE : View.GONE);
        dashboard.setVisibility(showPicker ? View.GONE : View.VISIBLE);
        parkedOnly.setVisibility(allowed ? View.GONE : View.VISIBLE);
        mascot.setVisibility(showMascot ? View.VISIBLE : View.GONE);
        TripStory entry = new TripStory(fuelLevel, measured == null ? null : measured.km(),
                measured == null ? null : measured.seconds());
        if (showMascot) {
            Expression face = automaticExpression(entry, measured);
            mascot.show(character, face);
            mascot.setContentDescription(character.label + ". " + face.note
                    + (settingsOpen ? " 설정 닫기" : " 설정 열기"));
        }
        arrivalDiary.setVisibility(arrival && !diaryInFront ? View.VISIBLE : View.GONE);
        settings.setVisibility(allowed && settingsOpen && !arrival && !diaryInFront ? View.VISIBLE : View.GONE);
        floatingToggle.setText(floatingEnabled() ? "다른 앱 위 표시 끄기" : "다른 앱 위에 표시");
        floatingHint.setText(floatingMessage);
        floatingHint.setVisibility(floatingMessage.isEmpty() ? View.GONE : View.VISIBLE);
        if (showPicker) renderPicker();
        String status = !hasSpeedPermission() ? "권한을 허용하면 다음 출발부터 기록해요."
                : active && incomplete ? "주행 신호가 끊겼어요. 주행 끝내기로 이번 기록을 정리해 주세요."
                : active ? "기록 중이에요. 중간에 주차해도 계속 이어져요."
                : incomplete ? "다음 출발부터 새로 기록해요." : "";
        recordStatus.setText(status);
        recordStatus.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
        finish.setVisibility(parked && active ? View.VISIBLE : View.GONE);
        previousDiary.setVisibility(diaryAvailable && !arrival ? View.VISIBLE : View.GONE);
        fuelPermission.setVisibility(hasFuelPermission() ? View.GONE : View.VISIBLE);
        speedPermission.setVisibility(!hasSpeedPermission() ? View.VISIBLE : View.GONE);
        if (diaryDialog != null) {
            if (diaryAvailable) updateDiary(entry, measured);
            else diaryDialog.dismiss();
        }
        if (launchDiary && diaryAvailable) {
            setCharacterPickerOpen(false);
            settingsOpen = false;
            picker.setVisibility(View.GONE);
            dashboard.setVisibility(View.VISIBLE);
            settings.setVisibility(View.GONE);
            showDiary(recorder, entry, measured);
        }
    }

    private void openCurrentDiary() {
        if (checkRestrictions()) {
            pendingOpenDiary = true;
            render();
        }
    }

    private void showDiary(TripRecorder recorder, TripStory entry, TripMeter.Summary measured) {
        if (diaryDialog != null) return;
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setTitle("오늘의 주행일기");
        int width = Math.min(880, getResources().getConfiguration().screenWidthDp - 40);
        int inset = width >= 540 ? 32 : 20;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(buttonShape(surface, line, 1));
        scroll.setClipToOutline(true);
        LinearLayout content = column();
        content.setPadding(dp(inset), dp(22), dp(inset), dp(24));
        scroll.addView(content);
        dialog.setContentView(scroll);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("오늘의 주행일기", width >= 540 ? 36 : 27, ink, true);
        title.setAccessibilityHeading(true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = button("닫기", surface, ink, line);
        close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close);
        content.addView(header, params(-1, -2, 0, 18));

        LinearLayout metrics = new LinearLayout(this);
        metric(metrics, "주행 거리").setText(entry.distance());
        metric(metrics, "주행 시간").setText(entry.duration());
        diaryFuel = metric(metrics, "남은 연료");
        content.addView(metrics, params(-1, -2, 0, 20));
        content.addView(divider(), params(-1, dp(1), 0, 22));

        LinearLayout story = new LinearLayout(this);
        boolean wide = width >= 600;
        story.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        story.setGravity(Gravity.CENTER_VERTICAL);
        MascotView diaryMascot = new MascotView(this);
        diaryMascot.show(character, automaticExpression(entry, measured));
        diaryMascot.setContentDescription(character.label);
        var imageSize = params(dp(wide ? 232 : 156), dp(wide ? 184 : 116), 0, wide ? 0 : 8);
        imageSize.setMarginEnd(dp(wide ? 24 : 0));
        story.addView(diaryMascot, imageSize);
        diaryCopy = text("", wide ? 24 : 22, ink, false);
        diaryCopy.setLineSpacing(dp(6), 1f);
        story.addView(diaryCopy, wide ? new LinearLayout.LayoutParams(0, -2, 1) : params(-1, -2, 0, 0));
        content.addView(story);
        updateDiary(entry, measured);

        diaryDialog = dialog;
        dialog.setOnDismissListener(v -> {
            if (diaryDialog == dialog) {
                diaryDialog = null;
                diaryFuel = diaryCopy = null;
                if (resumed && !isChangingConfigurations() && floatingEnabled()) finish();
                else render();
            }
        });
        var window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setDimAmount(.36f);
        dialog.show();
        window.setLayout(dp(width), -2);
        recorder.acknowledgeDiary();
    }

    private void updateDiary(TripStory entry, TripMeter.Summary measured) {
        diaryFuel.setText(!hasFuelPermission() ? "권한 필요" : loading ? "읽는 중"
                : entry.fuel() == null ? "조회 불가" : entry.fuel() + "%");
        diaryCopy.setText(entry.text() + "\n" + entry.feedback(measured));
    }

    private Expression automaticExpression(TripStory entry, TripMeter.Summary measured) {
        if (entry.low()) return Expression.FUEL_LOW;
        if (musicPlaying) return Expression.MUSIC;
        if (measured != null && measured.hardBraking()) return Expression.BRAKE;
        if (measured != null && measured.speedExceeded()) return Expression.SPEED;
        if (measured != null && measured.events().accelerationAt() >= 0) return Expression.ACCELERATION;
        return Expression.NEUTRAL;
    }

    private TextView metric(LinearLayout metrics, String label) {
        if (metrics.getChildCount() != 0) {
            LinearLayout.LayoutParams rule = new LinearLayout.LayoutParams(dp(1), -1);
            rule.setMarginEnd(dp(12));
            metrics.addView(divider(), rule);
        }
        LinearLayout box = column();
        box.setPaddingRelative(0, 0, dp(8), 0);
        box.addView(text(label, 16, muted, true));
        TextView value = text("—", 31, ink, true);
        value.setFontFeatureSettings("'tnum'");
        box.addView(value, params(-1, -2, 8, 0));
        metrics.addView(box, new LinearLayout.LayoutParams(0, -2, 1));
        return value;
    }

    private View divider() {
        View rule = new View(this);
        rule.setBackgroundColor(line);
        return rule;
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
    private GradientDrawable buttonShape(int fill, int outline, int outlineDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(10));
        if (outline != 0) d.setStroke(dp(outlineDp), outline);
        return d;
    }

    private RippleDrawable buttonBackground(int fill, int outline, boolean characterChoice) {
        StateListDrawable states = new StateListDrawable();
        if (characterChoice) {
            states.addState(new int[]{android.R.attr.state_selected}, buttonShape(selected, focus, 2));
        }
        states.addState(new int[]{android.R.attr.state_focused}, buttonShape(fill, focus, 2));
        states.addState(new int[]{}, buttonShape(fill, outline, 1));
        return new RippleDrawable(ColorStateList.valueOf(ripple), states, buttonShape(Color.WHITE, 0, 0));
    }

    private Button button(String label, int fill, int textColor, int outline) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{muted, textColor}));
        button.setTextSize(20);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setAllCaps(false);
        button.setMinimumHeight(dp(56));
        button.setMinHeight(dp(56));
        button.setMinimumWidth(dp(48));
        button.setMinWidth(dp(48));
        button.setPadding(dp(16), dp(11), dp(16), dp(11));
        button.setStateListAnimator(null);
        button.setBackground(buttonBackground(fill, outline, false));
        return button;
    }

    private BitmapDrawable thumbnail(Bitmap atlas, Expression pose, int heightDp) {
        int cellWidth = atlas.getWidth() / 2;
        int cellHeight = atlas.getHeight() / 3;
        int index = pose.spriteIndex();
        Bitmap face = Bitmap.createBitmap(atlas, index % 2 * cellWidth, index / 2 * cellHeight, cellWidth, cellHeight);
        BitmapDrawable icon = new BitmapDrawable(getResources(), face);
        int height = dp(heightDp);
        icon.setBounds(0, 0, Math.round(height * (float) cellWidth / cellHeight), height);
        return icon;
    }
}
