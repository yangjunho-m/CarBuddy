package app.carbuddy;

import java.util.Locale;

record TripStory(Integer battery, Float km, Integer seconds) {
    TripStory {
        if (battery != null && (battery < 0 || battery > 100)) battery = null;
        if (km != null && (!Float.isFinite(km) || km < 0)) km = null;
        if (seconds != null && seconds < 0) seconds = null;
    }

    boolean low() { return battery != null && battery <= 20; }
    boolean hasData() { return battery != null || km != null || seconds != null; }

    String distance() {
        if (km == null) return "—";
        if (km > 0 && km < 1) return Math.max(1, Math.round(km * 1000)) + " m";
        return String.format(Locale.KOREA, km % 1 == 0 ? "%.0f km" : "%.1f km", km);
    }

    String duration() {
        if (seconds == null) return "—";
        if (seconds < 60) return seconds == 0 && km != null && km > 0 ? "1초 미만" : seconds + "초";
        int minutes = seconds / 60;
        if (minutes < 60) return minutes + "분";
        return minutes / 60 + "시간 " + minutes % 60 + "분";
    }

    TripStory withMeasured(TripMeter.Summary measured) {
        if (measured == null || (km != null && km > 0 && seconds != null && seconds > 0)) return this;
        return new TripStory(battery, measured.km(), measured.seconds());
    }

    String text() {
        if (!hasData()) return "아직 차량에서 확인할 수 있는 기록이 없어. 잠시 후 다시 확인해 줘.";
        StringBuilder story = new StringBuilder();
        boolean hasDistance = km != null && km > 0;
        boolean hasTime = seconds != null && (seconds > 0 || hasDistance);
        if (hasDistance || hasTime) {
            story.append("이번에 우리 ");
            if (hasTime) story.append(duration()).append(" 동안 ");
            if (hasDistance) story.append(distance()).append("를 ");
            story.append("함께 달렸어. ");
        }
        if (battery != null) story.append("지금 남은 에너지는 ").append(battery).append("%야. ");
        return story.append(low() ? "다음 출발 전에 충전하면 좋겠어." : "함께해 줘서 고마워!").toString();
    }
}
