package com.supportmind.customer;

import com.supportmind.auth.AuthenticatedUser;
import com.supportmind.common.web.PageResponse;
import com.supportmind.common.web.SortableFields;
import com.supportmind.customer.CustomerService.CreateCustomerRequest;
import com.supportmind.customer.CustomerService.CustomerResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customers", description = "Customers of the organization")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    @Operation(summary = "List customers, optionally filtered by name or email")
    public PageResponse<CustomerResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                               @RequestParam(required = false) String search,
                                               @PageableDefault(size = 20, sort = "createdAt",
                                                       direction = Sort.Direction.DESC) Pageable pageable) {
        return customerService.list(user, search, SortableFields.validate(pageable, Set.of("createdAt", "fullName")));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Customer detail")
    public CustomerResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return customerService.get(user, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a customer (contact without portal account: email, WhatsApp or API conversations)")
    public CustomerResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                   @Valid @RequestBody CreateCustomerRequest request) {
        return customerService.create(user, request);
    }
}
