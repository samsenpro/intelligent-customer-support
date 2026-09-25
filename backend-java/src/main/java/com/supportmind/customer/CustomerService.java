package com.supportmind.customer;

import com.supportmind.audit.AuditEvent;
import com.supportmind.audit.AuditService;
import com.supportmind.auth.AuthenticatedUser;
import com.supportmind.common.web.PageResponse;
import com.supportmind.exception.ApiException;
import com.supportmind.exception.ErrorCode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final AuditService auditService;

    public CustomerService(CustomerRepository customerRepository, AuditService auditService) {
        this.customerRepository = customerRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> list(AuthenticatedUser user, String search, Pageable pageable) {
        String pattern = search == null || search.isBlank() ? "%"
                : "%" + search.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
                .replace("_", "\\_") + "%";
        return PageResponse.of(customerRepository.search(user.organizationId(), pattern, pageable),
                CustomerResponse::from);
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(AuthenticatedUser user, UUID id) {
        return customerRepository.findByIdAndOrganizationId(id, user.organizationId())
                .map(CustomerResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Customer not found"));
    }

    @Transactional
    public CustomerResponse create(AuthenticatedUser user, CreateCustomerRequest request) {
        String email = Customer.normalizeEmail(request.email());
        if (email != null && customerRepository.existsByOrganizationIdAndEmail(user.organizationId(), email)) {
            throw new ApiException(ErrorCode.CUSTOMER_EMAIL_ALREADY_EXISTS);
        }
        Customer customer;
        try {
            customer = customerRepository.saveAndFlush(new Customer(user.organizationId(), request.fullName(), email,
                    request.phone(), request.externalRef()));
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(ErrorCode.CUSTOMER_EMAIL_ALREADY_EXISTS);
        }
        auditService.record(AuditEvent.CUSTOMER_CREATED, user.organizationId(), user.id(), "Customer", customer.getId(),
                Map.of());
        return CustomerResponse.from(customer);
    }

    public record CreateCustomerRequest(
            @NotBlank @Size(max = 120) String fullName,
            @Email @Size(max = 254) String email,
            @Pattern(regexp = "^[+0-9 ()-]{6,30}$", message = "must be a valid phone number") String phone,
            @Size(max = 80) String externalRef) {
    }

    /** {@code hasPortalAccount}: el cliente tiene usuario y puede entrar al portal. */
    public record CustomerResponse(UUID id, String fullName, String email, String phone, String externalRef,
                                   boolean hasPortalAccount, Instant createdAt) {

        static CustomerResponse from(Customer customer) {
            return new CustomerResponse(customer.getId(), customer.getFullName(), customer.getEmail(),
                    customer.getPhone(), customer.getExternalRef(), customer.getUserId() != null,
                    customer.getCreatedAt());
        }
    }
}
