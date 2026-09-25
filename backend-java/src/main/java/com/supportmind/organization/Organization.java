package com.supportmind.organization;

import com.supportmind.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * Empresa cliente de la plataforma (tenant). Todos los datos de negocio pertenecen a una
 * organización y ninguna consulta cruza de una organización a otra.
 */
@Entity
@Table(name = "organizations")
public class Organization extends BaseEntity {

    @Column(nullable = false, length = 120)
    private String name;

    /** Identificador público (URL del portal de clientes y registro de clientes). */
    @Column(nullable = false, length = 60, updatable = false)
    private String slug;

    @Embedded
    private AiSettings aiSettings;

    @Version
    private long version;

    protected Organization() {
        // JPA
    }

    public Organization(String name, String slug, AiSettings aiSettings) {
        super(UUID.randomUUID());
        this.name = name;
        this.slug = slug;
        this.aiSettings = aiSettings;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public AiSettings getAiSettings() {
        return aiSettings;
    }
}
