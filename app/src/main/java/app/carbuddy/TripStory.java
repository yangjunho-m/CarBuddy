package app.carbuddy;

import java.util.Locale;

record TripStory(Integer fuel, Float km, Integer seconds) {
    TripStory {
        if (fuel != null && (fuel < 0 || fuel > 100)) fuel = null;
        if (km != null && (!Float.isFinite(km) || km < 0)) km = null;
        if (seconds != null && seconds < 0) seconds = null;
    }

    static Integer fuelPercent(Float levelMl, Float capacityMl) {
        if (levelMl == null || capacityMl == null || !Float.isFinite(levelMl) || !Float.isFinite(capacityMl)
                || levelMl < 0 || capacityMl <= 0 || levelMl > capacityMl) return null;
        return Math.round(levelMl / capacityMl * 100);
    }

    boolean low() { return fuel != null && fuel <= 20; }
    boolean hasData() { return fuel != null || km != null || seconds != null; }

    String distance() {
        if (km == null) return "—";
        if (km > 0 && km < 1) return Math.max(1, Math.round(km * 1000)) + " m";
        return String.format(Locale.KOREA, km % 1 == 0 ? "%.0f km" : "%.1f km", km);
    }

    String duration() {
        if (seconds == null) return "—";
        if (seconds < 60) return seconds == 0 && km != null && km > 0 ? "1초 미만" : seconds + "초";
        int minutes = seconds / 60;
        String minuteLabel = minutes < 60 ? minutes + "분" : minutes / 60 + "시간 " + minutes % 60 + "분";
        return seconds % 60 == 0 ? minuteLabel : minuteLabel + " " + seconds % 60 + "초";
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
        if (fuel != null) story.append("지금 남은 연료는 ").append(fuel).append("%야. ");
        return story.append(low() ? "다음 출발 전에 주유하면 좋겠어." : "함께해 줘서 고마워!").toString();
    }

    String feedback(TripMeter.Summary summary) {
        TripMeter.Events events = summary.events();
        StringBuilder notes = new StringBuilder();
        if (summary.speedExceeded()) {
            String limit = events.roadLimit() > 0 ? "내비의 제한속도 " + events.roadLimit()
                    : "기준 속도 " + TripMeter.SPEED_CUE_KPH;
            notes.append("\n").append(position(events.speedAt())).append(limit).append(" km/h를 넘었어.");
        }
        if (summary.hardBraking()) {
            notes.append("\n").append(position(events.brakeAt())).append("급감속해서 깜짝 놀랐어.");
        }
        if (events.accelerationAt() >= 0) {
            notes.append("\n").append(position(events.accelerationAt())).append("급가속이 있었어.");
        }
        return notes.length() == 0
                ? "\n기록에서 급가속, 급감속이나 속도 초과가 감지되지 않았어. 다음에도 편안하게 달리자!"
                : notes.append("\n다음에도 속도를 부드럽게 조절하며 안전하게 달리자.").toString();
    }

    private String position(int meters) {
        if (meters < 0) return "이번 주행 중 ";
        if (meters == 0) return "출발 직후 ";
        return "출발 후 약 " + new TripStory(null, meters / 1000f, null).distance() + " 지점에서 ";
    }
}
