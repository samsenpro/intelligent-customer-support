package com.supportmind.auth;

import com.supportmind.agent.Agent;
import com.supportmind.agent.AgentRepository;
import com.supportmind.auth.AuthDtos.UserResponse;
import com.supportmind.customer.Customer;
import com.supportmind.customer.CustomerRepository;
import com.supportmind.exception.ApiException;
import com.supportmind.exception.ErrorCode;
import com.supportmind.organization.Organization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Perfil completo de un usuario: organización y su perfil de agente o de cliente. */
@Service
public class UserProfileService {

    private final UserRepository userRepository;
    private final AgentRepository agentRepository;
    private final CustomerRepository customerRepository;

    public UserProfileService(UserRepository userRepository, AgentRepository agentRepository,
                              CustomerRepository customerRepository) {
        this.userRepository = userRepository;
        this.agentRepository = agentRepository;
        this.customerRepository = customerRepository;
    }

    @Transactional(readOnly = true)
    public UserResponse profile(UUID userId) {
        return profile(userRepository.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND)));
    }

    @Transactional(readOnly = true)
    public UserResponse profile(User user) {
        Organization organization = user.getOrganization();
        UUID agentId = user.getRole().isStaff()
                ? agentRepository.findByUserId(user.getId()).map(Agent::getId).orElse(null) : null;
        UUID customerId = user.getRole() == Role.CUSTOMER
                ? customerRepository.findByUserId(user.getId()).map(Customer::getId).orElse(null) : null;
        return new UserResponse(user.getId(), organization.getId(), organization.getName(), organization.getSlug(),
                user.getEmail(), user.getFullName(), user.getRole(), agentId, customerId, user.getCreatedAt());
    }
}
