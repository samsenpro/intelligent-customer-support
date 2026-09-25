package com.supportmind.auth;

import com.supportmind.agent.AgentService;
import com.supportmind.audit.AuditEvent;
import com.supportmind.audit.AuditService;
import com.supportmind.auth.AuthDtos.AuthResponse;
import com.supportmind.auth.AuthDtos.LoginRequest;
import com.supportmind.auth.AuthDtos.RegisterRequest;
import com.supportmind.customer.Customer;
import com.supportmind.customer.CustomerRepository;
import com.supportmind.exception.ApiException;
import com.supportmind.exception.ErrorCode;
import com.supportmind.organization.Organization;
import com.supportmind.organization.OrganizationRepository;
import com.supportmind.organization.OrganizationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationService organizationService;
    private final CustomerRepository customerRepository;
    private final AgentService agentService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final UserProfileService profiles;
    private final AuditService auditService;
    private final Clock clock;

    public AuthService(UserRepository userRepository, OrganizationRepository organizationRepository,
                       OrganizationService organizationService, CustomerRepository customerRepository,
                       AgentService agentService, PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager, JwtService jwtService,
                       RefreshTokenService refreshTokenService, UserProfileService profiles,
                       AuditService auditService, Clock clock) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.organizationService = organizationService;
        this.customerRepository = customerRepository;
        this.agentService = agentService;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.profiles = profiles;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Dos modos de registro público:
     * <ul>
     *   <li>Con {@code organizationName}: alta de una empresa nueva (SaaS) y su primer usuario, ADMIN.</li>
     *   <li>Con {@code organizationSlug}: un cliente se registra en el portal de una organización que lo
     *       permite. Nunca puede obtener otro rol que CUSTOMER.</li>
     * </ul>
     * El resto del equipo (supervisores y agentes) lo da de alta un ADMIN.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request, String ip) {
        String email = User.normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        User user = request.joinsExistingOrganization()
                ? registerCustomer(request, email, ip)
                : registerOrganization(request, email, ip);
        return tokensFor(user);
    }

    private User registerOrganization(RegisterRequest request, String email, String ip) {
        Organization organization = organizationService.create(request.organizationName().strip());
        User user = saveUser(new User(organization, email, passwordEncoder.encode(request.password()),
                request.fullName(), Role.ADMIN));
        agentService.createProfile(user);
        auditService.record(AuditEvent.ORGANIZATION_CREATED, organization.getId(), user.getId(), "Organization",
                organization.getId(), ip, Map.of("slug", organization.getSlug()));
        auditService.record(AuditEvent.USER_REGISTERED, organization.getId(), user.getId(), "User", user.getId(), ip,
                Map.of("role", Role.ADMIN.name()));
        return user;
    }

    private User registerCustomer(RegisterRequest request, String email, String ip) {
        Organization organization = organizationRepository
                .findBySlug(request.organizationSlug().strip().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Organization not found"));
        if (!organization.getAiSettings().isCustomerSignupEnabled()) {
            throw new ApiException(ErrorCode.SIGNUP_NOT_ALLOWED);
        }
        User user = saveUser(new User(organization, email, passwordEncoder.encode(request.password()),
                request.fullName(), Role.CUSTOMER));
        // Si el equipo ya había creado el cliente con ese email, la cuenta se vincula a él (conserva su historial)
        Customer customer = customerRepository.findByOrganizationIdAndEmail(organization.getId(), email)
                .filter(existing -> existing.getUserId() == null)
                .orElseGet(() -> new Customer(organization.getId(), request.fullName(), email, null, null));
        customer.linkUser(user.getId());
        customerRepository.save(customer);
        auditService.record(AuditEvent.USER_REGISTERED, organization.getId(), user.getId(), "User", user.getId(), ip,
                Map.of("role", Role.CUSTOMER.name(), "customerId", customer.getId().toString()));
        return user;
    }

    private User saveUser(User user) {
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // Dos registros simultáneos con el mismo email: gana el índice único de la base de datos
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String ip) {
        try {
            var authentication = authenticationManager.authenticate(UsernamePasswordAuthenticationToken
                    .unauthenticated(User.normalizeEmail(request.email()), request.password()));
            AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
            User user = userRepository.getReferenceById(principal.id());
            user.recordLogin(clock.instant());
            auditService.record(AuditEvent.USER_LOGIN, user.getOrganizationId(), user.getId(), "User", user.getId(), ip,
                    Map.of());
            return tokensFor(user);
        } catch (AuthenticationException ex) {
            // Mismo error para email inexistente, contraseña incorrecta o usuario deshabilitado
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
    }

    @Transactional(readOnly = true)
    public AuthResponse refresh(String refreshToken) {
        User user = refreshTokenService.consume(refreshToken)
                .flatMap(userRepository::findById)
                .filter(User::isEnabled)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REFRESH_TOKEN));
        return tokensFor(user);
    }

    private AuthResponse tokensFor(User user) {
        String accessToken = jwtService.createAccessToken(AuthenticatedUser.from(user));
        String refreshToken = refreshTokenService.issue(user.getId());
        return new AuthResponse(accessToken, refreshToken, "Bearer", jwtService.accessTokenTtlSeconds(),
                profiles.profile(user));
    }
}
