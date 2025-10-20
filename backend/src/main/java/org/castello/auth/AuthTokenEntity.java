package org.castello.auth;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="auth_tokens")
public class AuthTokenEntity {
    @Id private String token;           // UUID string — c'est ton Bearer
    @Column(nullable=false, name="user_id") private String userId;
    @Column(nullable=false, name="created_at") private Instant createdAt = Instant.now();
    public String getToken(){return token;} public void setToken(String t){this.token=t;}
    public String getUserId(){return userId;} public void setUserId(String u){this.userId=u;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant i){this.createdAt=i;}
}
