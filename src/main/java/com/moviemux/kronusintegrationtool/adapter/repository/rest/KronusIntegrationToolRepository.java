package com.moviemux.kronusintegrationtool.adapter.repository.rest;

import com.moviemux.kronusintegrationtool.domain.SendMailTemplate;
import com.moviemux.kronusintegrationtool.domain.repository.KronusCatalogRepository;
import com.moviemux.kronusintegrationtool.domain.repository.EmailGateway;

/** Transitional adapter contract for consumers not yet migrated to domain ports. */
@Deprecated
public interface KronusIntegrationToolRepository extends KronusCatalogRepository, EmailGateway {
    void sendMailTemplate(SendMailTemplate request);
}
