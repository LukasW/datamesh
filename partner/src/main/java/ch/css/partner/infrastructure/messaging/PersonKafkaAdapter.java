package ch.css.partner.infrastructure.messaging;

import ch.css.partner.domain.model.AdressTyp;
import ch.css.partner.domain.model.AhvNummer;
import ch.css.partner.domain.port.out.PersonEventPublisher;
import io.smallrye.reactive.messaging.kafka.KafkaRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@ApplicationScoped
public class PersonKafkaAdapter implements PersonEventPublisher {

    private static final Logger log = Logger.getLogger(PersonKafkaAdapter.class);

    @Inject
    @Channel("person-created")
    Emitter<String> personCreatedEmitter;

    @Inject
    @Channel("person-updated")
    Emitter<String> personUpdatedEmitter;

    @Inject
    @Channel("person-deleted")
    Emitter<String> personDeletedEmitter;

    @Inject
    @Channel("person-address-added")
    Emitter<String> personAddressAddedEmitter;

    @Inject
    @Channel("person-address-updated")
    Emitter<String> personAddressUpdatedEmitter;

    @Override
    public void publishPersonErstellt(String personId, String name, String vorname,
                                      AhvNummer ahv, LocalDate geburtsdatum) {
        String ahvStr = ahv != null ? ahv.formatted() : null;
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"PersonCreated\",\"personId\":\"%s\"," +
                "\"name\":\"%s\",\"vorname\":\"%s\",\"ahvNummer\":%s," +
                "\"geburtsdatum\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), personId, name, vorname,
                ahvStr != null ? "\"" + ahvStr + "\"" : "null",
                geburtsdatum, Instant.now());
        send(personCreatedEmitter, personId, json, "PersonCreated");
    }

    @Override
    public void publishPersonAktualisiert(String personId, String name, String vorname) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"PersonUpdated\",\"personId\":\"%s\"," +
                "\"name\":\"%s\",\"vorname\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), personId, name, vorname, Instant.now());
        send(personUpdatedEmitter, personId, json, "PersonUpdated");
    }

    @Override
    public void publishPersonGeloescht(String personId) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"PersonDeleted\",\"personId\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), personId, Instant.now());
        send(personDeletedEmitter, personId, json, "PersonDeleted");
    }

    @Override
    public void publishAdresseHinzugefuegt(String personId, String adressId,
                                            AdressTyp typ, LocalDate gueltigVon) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"AdresseHinzugefuegt\",\"personId\":\"%s\"," +
                "\"adressId\":\"%s\",\"adressTyp\":\"%s\",\"gueltigVon\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), personId, adressId, typ.name(), gueltigVon, Instant.now());
        send(personAddressAddedEmitter, personId, json, "AdresseHinzugefuegt");
    }

    @Override
    public void publishAdresseAktualisiert(String personId, String adressId,
                                            LocalDate gueltigVon, LocalDate gueltigBis) {
        String json = String.format(
                "{\"eventId\":\"%s\",\"eventType\":\"AdresseAktualisiert\",\"personId\":\"%s\"," +
                "\"adressId\":\"%s\",\"gueltigVon\":\"%s\",\"gueltigBis\":\"%s\",\"timestamp\":\"%s\"}",
                UUID.randomUUID(), personId, adressId, gueltigVon,
                gueltigBis != null ? gueltigBis.toString() : null, Instant.now());
        send(personAddressUpdatedEmitter, personId, json, "AdresseAktualisiert");
    }

    private void send(Emitter<String> emitter, String key, String json, String eventType) {
        try {
            emitter.send(KafkaRecord.of(key, json));
        } catch (Exception e) {
            log.warnf("Failed to publish %s event for %s: %s", eventType, key, e.getMessage());
        }
    }
}
