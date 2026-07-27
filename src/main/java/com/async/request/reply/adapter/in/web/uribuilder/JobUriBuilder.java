package com.async.request.reply.adapter.in.web.uribuilder;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * Constrói as URIs canônicas dos recursos de job a partir do request context atual.
 * Injetável para que o controller fique livre do plumbing de montagem de URI.
 */
@Component
public class JobUriBuilder {

    public URI status(String jobId) {
        return base().path("/jobs/{id}/status").buildAndExpand(jobId).toUri();
    }

    /** Stream de eventos (SSE): o caminho normal para saber que o job terminou. */
    public URI events(String jobId) {
        return base().path("/jobs/{id}/events").buildAndExpand(jobId).toUri();
    }

    private ServletUriComponentsBuilder base() {
        // scheme://host:port/contextPath — derivado da request em andamento
        return ServletUriComponentsBuilder.fromCurrentContextPath();
    }
}
