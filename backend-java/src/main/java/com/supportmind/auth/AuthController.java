package com.supportmind.auth;

import com.supportmind.auth.AuthDtos.AuthResponse;
import com.supportmind.auth.AuthDtos.LoginRequest;
import com.supportmind.auth.AuthDtos.RefreshTokenRequest;
import com.supportmind.auth.AuthDtos.RegisterRequest;
import com.supportmind.auth.AuthDtos.UserResponse;
import com.supportmind.common.ratelimit.RateLimited;
import com.supportmind.common.web.ClientIp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Registration, login and token refresh")
public class AuthController {

    private final AuthService authService;
    private final UserProfileService profiles;

    public AuthController(AuthService authService, UserProfileService profiles) {
        this.authService = authService;
        this.profiles = profiles;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimited(policy = "auth")
    @Operation(summary = "Register a new organization (ADMIN) or a customer of an existing organization")
    @ApiResponse(responseCode = "201", description = "User created")
    @ApiResponse(responseCode = "400", description = "Invalid data")
    @ApiResponse(responseCode = "403", description = "The organization does not accept customer sign-ups")
    @ApiResponse(responseCode = "409", description = "Email already registered")
    @ApiResponse(responseCode = "429", description = "Too many attempts")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        return authService.register(request, ClientIp.of(http));
    }

    @PostMapping("/login")
    @RateLimited(policy = "auth")
    @Operation(summary = "Authenticate with email and password")
    @ApiResponse(responseCode = "200", description = "Authenticated")
    @ApiResponse(responseCode = "401", description = "Invalid credentials")
    @ApiResponse(responseCode = "429", description = "Too many attempts")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return authService.login(request, ClientIp.of(http));
    }

    @PostMapping("/refresh")
    @RateLimited(policy = "auth")
    @Operation(summary = "Exchange a refresh token for a new token pair (the old refresh token is revoked)")
    @ApiResponse(responseCode = "200", description = "New tokens issued")
    @ApiResponse(responseCode = "401", description = "Refresh token invalid, expired or already used")
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Profile of the authenticated user")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return profiles.profile(user.id());
    }
}
