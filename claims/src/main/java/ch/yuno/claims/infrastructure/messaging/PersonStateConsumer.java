package ch.yuno.claims.infrastructure.messaging;

import ch.yuno.claims.domain.port.out.PartnerSearchViewRepository;
import ch.yuno.claims.domain.port.out.PiiDecryptor;
import ch.yuno.claims.infrastructure.messaging.acl.PersonStateEventTranslator;
import ch.yuno.claims.infrastructure.messaging.acl.PersonStateEventTranslator.TranslationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Kafka consumer for person.v1.state (ECST compacted topic).
 * Materialises a local PartnerSearchView read model for FNOL partner search.
 */
@ApplicationScoped
public class PersonStateConsumer {

    private static final Logger LOG = Logger.getLogger(PersonStateConsumer.class);
    private final PersonStateEventTranslator translator;

    @Inject
    PartnerSearchViewRepository repository;

    @Inject
    PersonStateConsumer(PiiDecryptor piiDecryptor) {
        this.translator = new PersonStateEventTranslator(new ObjectMapper(), piiDecryptor);
    }

    @Transactional
    @Incoming("partner-state-in")
    public void onPersonState(String payload) {
        try {
            TranslationResult result = translator.translate(payload);
            switch (result) {
                case TranslationResult.PartnerUpsert upsert -> {
                    repository.upsert(upsert.view());
                    LOG.debugf("Upserted PartnerSearchView: %s → %s %s",
                            upsert.view().partnerId(), upsert.view().firstName(), upsert.view().lastName());
                }
                case TranslationResult.PartnerDeletion deletion -> {
                    repository.deleteByPartnerId(deletion.partnerId());
                    LOG.infof("Deleted PartnerSearchView: %s (GDPR tombstone)", deletion.partnerId());
                }
            }
        } catch (Exception e) {
            LOG.errorf("Failed to process person.v1.state: %s", e.getMessage());
            throw new RuntimeException("Failed to process person.v1.state", e);
        }
    }
}

