package com.supportmind.customer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<Customer> findByUserId(UUID userId);

    Optional<Customer> findByOrganizationIdAndEmail(UUID organizationId, String email);

    boolean existsByOrganizationIdAndEmail(UUID organizationId, String email);

    List<Customer> findByIdIn(Collection<UUID> ids);

    /** {@code pattern} es un patrón LIKE en minúsculas; "%" devuelve todos los clientes. */
    @Query("""
            SELECT c FROM Customer c
            WHERE c.organizationId = :organizationId
              AND (LOWER(c.fullName) LIKE :pattern ESCAPE '\\' OR LOWER(c.email) LIKE :pattern ESCAPE '\\')""")
    Page<Customer> search(@Param("organizationId") UUID organizationId, @Param("pattern") String pattern,
                          Pageable pageable);
}
