package com.smartexam.controller;
import com.smartexam.common.ApiResponse; import com.smartexam.dto.LoginRequest; import com.smartexam.entity.Users; import com.smartexam.security.JwtService; import com.smartexam.service.UsersService; import com.smartexam.vo.LoginVO; import com.smartexam.vo.UserVO; import jakarta.validation.Valid; import org.springframework.http.*; import org.springframework.security.core.Authentication; import org.springframework.security.crypto.password.PasswordEncoder; import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/auth") public class AuthController {
 private final UsersService users; private final PasswordEncoder encoder; private final JwtService jwt;
 public AuthController(UsersService users,PasswordEncoder encoder,JwtService jwt){this.users=users;this.encoder=encoder;this.jwt=jwt;}
 @PostMapping("/login") public ResponseEntity<ApiResponse<LoginVO>> login(@Valid @RequestBody LoginRequest request){Users u=users.findByUsername(request.username());if(u==null||!Integer.valueOf(1).equals(u.getStatus())||!encoder.matches(request.password(),u.getPasswordHash()))return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.failure("用户名或密码错误","UNAUTHORIZED"));return ResponseEntity.ok(ApiResponse.success(new LoginVO(jwt.generate(u),UserVO.from(u))));}
 @GetMapping("/me") public ApiResponse<UserVO> me(Authentication auth){Users u=users.findByUsername(auth.getName());return ApiResponse.success(UserVO.from(u));}
}
