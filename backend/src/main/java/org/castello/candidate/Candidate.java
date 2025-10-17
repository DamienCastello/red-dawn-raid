package org.castello.candidate;

public class Candidate {
    private final String id;
    private final String nickname;
    private final String token;
    private final String gameId;

    public Candidate(String id, String nickname, String token, String gameId) {
        this.id = id;
        this.nickname = nickname;
        this.token = token;
        this.gameId = gameId;
    }

    public String getId() { return id; }
    public String getNickname() { return nickname; }
    public String getToken() { return token; }
    public String getGameId() { return gameId; }
}
