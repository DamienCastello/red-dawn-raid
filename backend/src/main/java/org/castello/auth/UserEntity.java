package org.castello.auth;

import jakarta.persistence.*;

@Entity
@Table(name = "users", indexes = @Index(name = "uk_users_username", columnList = "username", unique = true))
public class UserEntity {
    @Id private String id;
    @Column(nullable=false, unique=true) private String username;
    @Column(nullable=false, name="password_hash") private String passwordHash;
    public String getId(){return id;} public void setId(String id){this.id=id;}
    public String getUsername(){return username;} public void setUsername(String u){this.username=u;}
    public String getPasswordHash(){return passwordHash;} public void setPasswordHash(String h){this.passwordHash=h;}
}
