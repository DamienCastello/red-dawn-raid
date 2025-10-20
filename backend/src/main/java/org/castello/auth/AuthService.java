package org.castello.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@Service
public class AuthService {
    private final UserRepository users;
    private final AuthTokenRepository tokens;
    private final PasswordEncoder bcrypt;

    public AuthService(UserRepository users, AuthTokenRepository tokens, PasswordEncoder bcrypt){
        this.users=users; this.tokens=tokens; this.bcrypt=bcrypt;
    }

    public String signup(String username, String password){
        if(username==null||username.isBlank()||password==null||password.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"username & password required");
        if(users.findByUsername(username).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"username already taken");
        var u=new UserEntity();
        u.setId(UUID.randomUUID().toString());
        u.setUsername(username.trim());
        u.setPasswordHash(bcrypt.encode(password));
        users.save(u);
        return u.getId();
    }

    public String login(String username, String password){
        var u=users.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,"invalid credentials"));
        if(!bcrypt.matches(password,u.getPasswordHash()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"invalid credentials");
        var t=new AuthTokenEntity();
        t.setToken(UUID.randomUUID().toString());
        t.setUserId(u.getId());
        tokens.save(t);
        return t.getToken(); // Bearer
    }

    /** Retourne l’utilisateur à partir d’un header Authorization: Bearer <authToken> */
    public UserEntity requireUser(String authorization){
        if(authorization==null || !authorization.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"missing auth token");
        String token=authorization.substring(7);
        var at=tokens.findById(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,"invalid auth token"));
        return users.findById(at.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,"user not found"));
    }
}
