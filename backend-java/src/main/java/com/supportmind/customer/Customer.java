package com.supportmind.customer;

import com.supportmind.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.Locale;
import java.util.UUID;

/**
 * Cliente final de una organización. Puede tener cuenta en el portal (userId) o existir solo como
 * contacto creado por el equipo (conversaciones por email, WhatsApp o API).
 */
@Entity
@Table(name = "customers")
public class Customer extends BaseEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(length = 254)
    private String email;

    @Column(length = 30)
    private String phone;

    @Column(name = "external_ref", length = 80)
    private String externalRef;

    protected Customer() {
        // JPA
    }

    public Customer(UUID organizationId, String fullName, String email, String phone, String externalRef) {
        super(UUID.randomUUID());
        this.organizationId = organizationId;
        this.fullName = fullName.strip();
        this.email = normalizeEmail(email);
        this.phone = blankToNull(phone);
        this.externalRef = blankToNull(externalRef);
    }

    public static String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public void linkUser(UUID userId) {
        this.userId = userId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getExternalRef() {
        return externalRef;
    }
}
