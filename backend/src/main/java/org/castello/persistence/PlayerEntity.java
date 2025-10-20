package org.castello.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "players",
        indexes = {
                @Index(name = "idx_players_game", columnList = "game_id"),
                @Index(name = "uk_players_user", columnList = "user_id", unique = true)
        })
public class PlayerEntity {
    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "game_id", nullable = false)
    private String gameId;

    @Column(nullable = false)
    private String username;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
}
