package com.supportmind.knowledge;

import com.supportmind.auth.AuthenticatedUser;
import com.supportmind.common.ratelimit.RateLimited;
import com.supportmind.common.web.PageResponse;
import com.supportmind.common.web.SortableFields;
import com.supportmind.knowledge.KnowledgeDtos.KnowledgeRequest;
import com.supportmind.knowledge.KnowledgeDtos.KnowledgeResponse;
import com.supportmind.knowledge.KnowledgeDtos.KnowledgeSummaryResponse;
import com.supportmind.knowledge.KnowledgeDtos.SearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge")
@Tag(name = "Knowledge base", description = "Company documents used by the AI to answer (RAG)")
@SecurityRequirement(name = "bearerAuth")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(summary = "List knowledge documents with their indexing status")
    public PageResponse<KnowledgeSummaryResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                       @RequestParam(required = false) KnowledgeStatus status,
                                                       @RequestParam(required = false) KnowledgeType type,
                                                       @RequestParam(required = false) String search,
                                                       @PageableDefault(size = 20, sort = "updatedAt",
                                                               direction = Sort.Direction.DESC) Pageable pageable) {
        return knowledgeService.list(user, status, type, search,
                SortableFields.validate(pageable, Set.of("updatedAt", "createdAt", "title")));
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @RateLimited(policy = "knowledge-search")
    @Operation(summary = "Semantic search in the published knowledge base (vector search)")
    public SearchResponse search(@AuthenticationPrincipal AuthenticatedUser user,
                                 @RequestParam @NotBlank @Size(max = 500) String q,
                                 @RequestParam(required = false) @Min(1) @Max(20) Integer topK) {
        return knowledgeService.search(user, q, topK);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(summary = "Knowledge document with its content")
    public KnowledgeResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return knowledgeService.get(user, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a document; if PUBLISHED it is chunked, embedded and indexed asynchronously (ADMIN)")
    public KnowledgeResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                    @Valid @RequestBody KnowledgeRequest request) {
        return knowledgeService.create(user, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update a document; publishing re-indexes it and archiving removes it from the AI (ADMIN)")
    public KnowledgeResponse update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                                    @Valid @RequestBody KnowledgeRequest request) {
        return knowledgeService.update(user, id, request);
    }

    @PostMapping("/{id}/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Queue the re-indexing of a published document (ADMIN)")
    public KnowledgeResponse reindex(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return knowledgeService.reindex(user, id);
    }
}
