package app.carbuddy;

final class TripStoryCheck {
    public static void main(String[] args) {
        var low = new TripStory(20, 42f, 3720);
        assert low.low() && low.text().contains("1시간 2분 동안 42 km") && low.text().contains("남은 연료는 20%");
        assert low.text().contains("주유") && low.duration().equals("1시간 2분");
        assert new TripStory(0, null, null).low();
        assert TripStory.fuelPercent(12_000f, 60_000f) == 20;
        assert TripStory.fuelPercent(0f, 60_000f) == 0;
        assert TripStory.fuelPercent(60_000f, 60_000f) == 100;
        assert TripStory.fuelPercent(1f, 0f) == null;
        assert TripStory.fuelPercent(60_001f, 60_000f) == null;
        assert TripStory.fuelPercent(Float.NaN, 60_000f) == null;
        assert TripStory.fuelPercent(null, 60_000f) == null;
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

        var guided = new TripMeter(new TripMeter.Checkpoint(false, false, 0, 0, false, false));
        guided.gear(true, 0);
        guided.roadSpeedLimit(60);
        guided.gear(false, second);
        guided.speed(second, 0);
        guided.speed(2 * second, 18);
        guided.speed(3 * second, 0);
        guided.gear(true, 4 * second);
        assert guided.active() && !guided.diaryReady(); // Intermediate park.
        assert guided.summary().events().roadLimit() == 60;
        assert guided.summary().events().accelerationAt() == 9;
        assert guided.summary().events().brakeAt() == 18;

        guided = new TripMeter(guided.checkpoint());
        guided.gear(true, 15 * second);
        guided.gear(false, 16 * second);
        guided.speed(16 * second, 2);
        guided.speed(17 * second, 4);
        assert guided.destinationArrived() && !guided.diaryReady() && guided.active();
        guided.gear(true, 18 * second);
        assert guided.diaryReady() && !guided.active() && distance(guided).equals("21 m");
        var feedback = new TripStory(null, 0.021f, 3).feedback(guided.summary());
        assert feedback.contains("9 m 지점에서 내비의 제한속도 60 km/h")
                && feedback.contains("18 m 지점에서 급감속") && feedback.contains("급가속");
        guided = new TripMeter(guided.checkpoint());
        guided.gear(true, 20 * second);
        assert guided.acknowledgeDiary() && !guided.diaryReady() && !guided.destinationArrived();

        guided.gear(false, 21 * second);
        guided.speed(21 * second, 4);
        guided.speed(22 * second, 0);
        guided.gear(true, 23 * second);
        assert guided.active() && !guided.diaryReady();
        assert guided.destinationArrived() && guided.diaryReady() && !guided.active();
    }

    private static String distance(TripMeter meter) {
        return new TripStory(18, meter.summary().km(), meter.summary().seconds()).distance();
    }
}
