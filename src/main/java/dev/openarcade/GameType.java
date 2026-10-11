package dev.openarcade;

public enum GameType {
    SPLEEF("Spleef"),
    TNT_RUN("TNT Run"),
    BLOCK_PARTY("Block Party");

    private final String display;

    GameType(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }
}
