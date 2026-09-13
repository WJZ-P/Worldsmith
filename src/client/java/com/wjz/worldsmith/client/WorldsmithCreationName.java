package com.wjz.worldsmith.client;

/** A suggested save name follows pack selection until the player supplies a different name. */
final class WorldsmithCreationName {
    private final String defaultName;
    private String nativeName;
    private String suggestedName;

    WorldsmithCreationName(String defaultName) { this.defaultName = defaultName; }

    String selectPack(String current, String title) {
        if (nativeName == null) nativeName = current;
        if (title == null || title.isBlank()) return current;
        boolean replace = current.isBlank() || current.equals(defaultName) || current.equals(suggestedName);
        suggestedName = title;
        return replace ? title : current;
    }

    String selectNative(String current) {
        String result = current.equals(suggestedName) && nativeName != null ? nativeName : current;
        suggestedName = null;
        nativeName = null;
        return result;
    }
}
