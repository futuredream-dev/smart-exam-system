package com.smartexam.security;
import com.smartexam.entity.Users; import io.jsonwebtoken.Claims; import io.jsonwebtoken.Jwts; import io.jsonwebtoken.security.Keys; import org.springframework.beans.factory.annotation.Value; import org.springframework.stereotype.Service;
import javax.crypto.SecretKey; import java.nio.charset.StandardCharsets; import java.util.Date;
@Service public class JwtService {
 private final SecretKey key; private final long expiration;
 public JwtService(@Value("${jwt.secret}") String secret,@Value("${jwt.expiration}") long expiration){this.key=Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));this.expiration=expiration;}
 public String generate(Users user){return generate(user, expiration);}
 /** 指定有效期主要用于认证测试；生产登录仍使用配置的默认有效期。 */
 public String generate(Users user,long validityMillis){return Jwts.builder().subject(user.getUsername()).claim("userId",user.getId()).claim("role",user.getRole()).issuedAt(new Date()).expiration(new Date(System.currentTimeMillis()+validityMillis)).signWith(key).compact();}
 public Claims parse(String token){return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();}
 }
