package org.castello.game;

public class CenterBoard {
    private String playerId;
    private String card;
    private boolean faceUp;

    public CenterBoard(String candidateId, String card, boolean faceUp) {
        this.playerId = playerId;
        this.card = card;
        this.faceUp = faceUp;
    }

    public String getPlayerId() { return playerId; }
    public String getCard() { return card; }
    public boolean isFaceUp() { return faceUp; }
    public void setFaceUp(boolean faceUp) { this.faceUp = faceUp; }
}
