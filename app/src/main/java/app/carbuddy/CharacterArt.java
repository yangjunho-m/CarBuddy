package app.carbuddy;

import android.content.Context;
import android.content.SharedPreferences;

enum CharacterArt {
    MOMOKA("모모카", R.drawable.momoka),
    DINOCA("디노카", R.drawable.dinoca),
    NYANGCA("냥카", R.drawable.nyangca),
    POCA("포카", R.drawable.poca);

    final String label;
    final int image;
    static final String PICKED = "character_picked";
    static final String CHOICE = "character";
    static final String FLOATING = "floating_companion";

    static SharedPreferences preferences(Context context) {
        // Keep the characters saved by earlier versions of MainActivity.
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
    }

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
