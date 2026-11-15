package app.carbuddy;

enum Expression {
    NEUTRAL("", "지금 차의 상태를 함께 살펴봐요."),
    FUEL_LOW("연료 부족", "연료가 부족해서 땀이 나요."),
    MUSIC("음악 재생", "음악이 들리니 신나요."),
    BRAKE("급감속", "갑자기 속도가 줄어 깜짝 놀랐어요."),
    SPEED("속도 초과", "정해 둔 속도를 넘어서 긴장했어요."),
    ACCELERATION("급가속", "갑자기 속도가 올라 깜짝 놀랐어요.");

    final String label;
    final String note;

    Expression(String label, String note) {
        this.label = label;
        this.note = note;
    }

    int spriteIndex() { return this == ACCELERATION ? BRAKE.ordinal() : ordinal(); }
}
