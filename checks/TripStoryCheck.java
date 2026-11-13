package app.carbuddy;

final class TripStoryCheck {
    public static void main(String[] args) {
        var low = new TripStory(20, 42f, 3720);
        assert low.low() && low.text().contains("1시간 2분 동안 42 km") && low.text().contains("20%");
        assert low.text().contains("충전") && low.duration().equals("1시간 2분");
        assert !new TripStory(21, 1f, 0).low();
        assert new TripStory(null, 1f, 0).duration().equals("1초 미만");
        var invalid = new TripStory(101, Float.NaN, -1);
        assert !invalid.hasData() && !invalid.low();
        assert !new TripStory(null, 12f, null).text().contains("%");

        var meter = new TripMeter();
        meter.gear(true, 0);
        meter.gear(false, 1_000_000_000L);
        meter.speed(1_000_000_000L, 10);
        meter.speed(2_000_000_000L, 10);
        meter.speed(3_000_000_000L, 0);
        meter.speed(4_000_000_000L, 0);
        meter.gear(true, 4_000_000_000L);
        var measured = new TripStory(18, null, null).withMeasured(meter.last());
        assert measured.distance().equals("15 m") && measured.duration().equals("2초");
        assert measured.text().contains("2초 동안 15 m");
        assert low.withMeasured(meter.last()).equals(low);
        meter.gear(false, 1_000_000_000L);
        assert meter.last() == null;
        meter.speed(1_000_000_000L, 10);
        meter.speed(7_000_000_000L, 10);
        meter.speed(8_000_000_000L, 10);
        meter.gear(true, 8_000_000_000L);
        assert meter.last() == null;

        meter.gear(false, 9_000_000_000L);
        meter.speed(9_000_000_000L, -10);
        meter.speed(10_000_000_000L, -10);
        meter.gear(true, 16_000_000_000L);
        assert meter.last() == null;
    }
}
