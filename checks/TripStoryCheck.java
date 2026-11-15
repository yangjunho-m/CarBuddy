package app.carbuddy;

final class TripStoryCheck {
    public static void main(String[] args) {
        var low = new TripStory(20, 42f, 3720);
        assert low.low() && low.text().contains("1시간 2분 동안 42 km") && low.text().contains("20%");
        assert low.text().contains("충전") && low.duration().equals("1시간 2분");
        assert !new TripStory(21, 1f, 0).low();
        assert new TripStory(null, 1f, 0).duration().equals("1초 미만");
        assert new TripStory(50, 2f, 72).duration().equals("1분 12초");
        var invalid = new TripStory(101, Float.NaN, -1);
        assert !invalid.hasData() && !invalid.low();
        assert !new TripStory(null, 12f, null).text().contains("%");

        var meter = new TripMeter(new TripMeter.Checkpoint(false, false, 0, 0, false, false));
        final long second = 1_000_000_000L;
        meter.gear(true, 0);
        assert !meter.finish();
        meter.gear(false, second);
        assert !meter.finish();
        meter.speed(second, 10);
        meter.speed(2 * second, 10);
        meter.speed(3 * second, 0);
        meter.speed(4 * second, 0);
        meter.gear(true, 4 * second);
        assert meter.active() && meter.parked();
        assert distance(meter).equals("15 m") && meter.summary().seconds() == 2;
        assert meter.summary().hardBraking() && !meter.summary().speedExceeded();

        // Restart the app while parked and resume after a long stop.
        meter = new TripMeter(meter.checkpoint());
        meter.gear(true, 600 * second);
        meter.speed(601 * second, 10);
        meter.gear(false, 602 * second);
        meter.speed(602 * second, 25);
        meter.speed(603 * second, 25);
        meter.speed(604 * second, 0);
        meter.gear(true, 605 * second);
        assert meter.active() && distance(meter).equals("53 m") && meter.summary().seconds() == 4;
        assert meter.summary().hardBraking() && meter.summary().speedExceeded();
        assert meter.finish() && !meter.active() && !meter.finish();

        meter = new TripMeter(meter.checkpoint());
        meter.gear(true, 700 * second);
        assert distance(meter).equals("53 m");
        meter.gear(false, 701 * second);
        assert meter.active() && meter.summary() == null;
        meter.speed(701 * second, 10);
        meter.speed(708 * second, 10);
        meter.gear(true, 709 * second);
        assert meter.incomplete() && meter.summary() == null && meter.finish();

        meter.gear(false, 710 * second);
        meter.speed(710 * second, 10);
        meter.speed(712 * second, 0);
        meter.gear(true, 713 * second);
        assert !meter.summary().speedExceeded() && !meter.summary().hardBraking();
        meter.gear(false, 714 * second);
        meter = new TripMeter(meter.checkpoint());
        meter.gear(true, 715 * second);
        assert meter.active() && meter.incomplete() && meter.summary() == null;
    }

    private static String distance(TripMeter meter) {
        return new TripStory(18, meter.summary().km(), meter.summary().seconds()).distance();
    }
}
