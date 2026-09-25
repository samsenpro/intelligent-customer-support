package com.supportmind.organization;

import com.supportmind.agent.AgentService;
import com.supportmind.audit.AuditEvent;
import com.supportmind.audit.AuditService;
import com.supportmind.auth.AuthenticatedUser;
import com.supportmind.auth.Role;
import com.supportmind.auth.User;
import com.supportmind.auth.UserRepository;
import com.supportmind.common.web.PageResponse;
import com.supportmind.exception.ApiException;
import com.supportmind.exception.ErrorCode;
import com.supportmind.organization.OrganizationDtos.CreateMemberRequest;
import com.supportmind.organization.OrganizationDtos.MemberResponse;
import com.supportmind.organization.OrganizationDtos.OrganizationResponse;
import com.supportmind.organization.OrganizationDtos.UpdateAiSettingsRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;

@Service
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final AgentService agentService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final BigDecimal defaultConfidenceThreshold;
    private final SecureRandom random = new SecureRandom();

    public OrganizationService(OrganizationRepository organizationRepository, UserRepository userRepository,
                               AgentService agentService, PasswordEncoder passwordEncoder, AuditService auditService,
                               @Value("${supportmind.ai.default-confidence-threshold:0.55}")
                               BigDecimal defaultConfidenceThreshold) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.agentService = agentService;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.defaultConfidenceThreshold = defaultConfidenceThreshold;
    }

    /** Crea una organización con la configuración de IA por defecto y un slug único. */
    @Transactional
    public Organization create(String name) {
        String base = Slugs.from(name);
        String slug = base;
        while (organizationRepository.existsBySlug(slug)) {
            slug = base + "-" + HexFormat.of().formatHex(random.generateSeed(2));
        }
        return organizationRepository.save(new Organization(name, slug, AiSettings.defaults(defaultConfidenceThreshold)));
    }

    @Transactional(readOnly = true)
    public OrganizationResponse get(AuthenticatedUser user) {
        return OrganizationResponse.from(find(user));
    }

    @Transactional
    public OrganizationResponse updateAiSettings(AuthenticatedUser user, UpdateAiSettingsRequest request) {
        Organization organization = find(user);
        organization.getAiSettings().update(request.autoReplyEnabled(), request.confidenceThreshold(),
                request.noContextAction(), request.maxFailedAiAnswers(),
                request.sensitiveCategories().stream().map(Enum::name).sorted().toList(), request.handoffOnUrgent(),
                request.autoTicketEnabled(), request.customerSignupEnabled(), request.summaryAfterMessages());
        organizationRepository.saveAndFlush(organization);
        auditService.record(AuditEvent.ORGANIZATION_SETTINGS_UPDATED, organization.getId(), user.id(),
                "Organization", organization.getId(), Map.of(
                        "autoReplyEnabled", request.autoReplyEnabled(),
                        "confidenceThreshold", request.confidenceThreshold(),
                        "noContextAction", request.noContextAction().name()));
        return OrganizationResponse.from(organization);
    }

    @Transactional(readOnly = true)
    public PageResponse<MemberResponse> members(AuthenticatedUser user, Pageable pageable) {
        return PageResponse.of(userRepository.findByOrganization_Id(user.organizationId(), pageable),
                MemberResponse::from);
    }

    /** El ADMIN da de alta al equipo de soporte. Cada miembro recibe su perfil de agente. */
    @Transactional
    public MemberResponse createMember(AuthenticatedUser admin, CreateMemberRequest request) {
        String email = User.normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        Role role = Role.valueOf(request.role());
        User user;
        try {
            user = userRepository.saveAndFlush(new User(find(admin), email, passwordEncoder.encode(request.password()),
                    request.fullName(), role));
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        agentService.createProfile(user);
        auditService.record(AuditEvent.USER_CREATED, admin.organizationId(), admin.id(), "User", user.getId(),
                Map.of("role", role.name()));
        return MemberResponse.from(user);
    }

    private Organization find(AuthenticatedUser user) {
        return organizationRepository.findById(user.organizationId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Organization not found"));
    }
}
