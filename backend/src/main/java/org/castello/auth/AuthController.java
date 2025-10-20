package org.castello.auth;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/auth")
public class AuthController {
    private record SignupReq(@NotBlank String username,@NotBlank String password){}
    private record LoginReq(@NotBlank String username,@NotBlank String password){}
    private record AuthResp(String authToken,String userId,String username){}

    private final AuthService auth;
    public AuthController(AuthService auth){this.auth=auth;}

    @PostMapping("/signup") @ResponseStatus(HttpStatus.CREATED)
    public AuthResp signup(@RequestBody SignupReq req){
        String userId=auth.signup(req.username(), req.password());
        String token=auth.login(req.username(), req.password()); // auto-login
        return new AuthResp(token,userId,req.username());
    }

    @PostMapping("/login")
    public AuthResp login(@RequestBody LoginReq req){
        String token=auth.login(req.username(), req.password());
        var u=auth.requireUser("Bearer "+token);
        return new AuthResp(token,u.getId(),u.getUsername());
    }
}
