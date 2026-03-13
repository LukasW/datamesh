package ch.css.partner.infrastructure.dev;

import ch.css.partner.domain.model.AdressTyp;
import ch.css.partner.domain.model.Geschlecht;
import ch.css.partner.domain.service.PersonApplicationService;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDate;

/**
 * Seed realistic test data in dev mode.
 * Only active when the "dev" build profile is active (i.e. `quarkus:dev`).
 * Skips insertion when persons already exist (idempotent across hot reloads).
 */
@ApplicationScoped
@IfBuildProfile("dev")
public class DevDataInitializer {

    private static final Logger log = Logger.getLogger(DevDataInitializer.class);

    @Inject
    PersonApplicationService personService;

    void onStart(@Observes StartupEvent event) {
        if (!personService.listAllPersonen().isEmpty()) {
            log.info("Dev data already present, skipping seed.");
            return;
        }
        log.info("Seeding dev test data...");
        seedMaxMuster();
        seedAnnaMueller();
        seedHansMeier();
        seedMariaBraun();
        seedPeterSchmid();
        log.info("Dev data seeded: 5 Personen.");
    }

    // ── Persons ───────────────────────────────────────────────────────────────

    /**
     * Max Muster – zwei aktuelle Adresstypen (WOHNADRESSE + KORRESPONDENZADRESSE).
     */
    private void seedMaxMuster() {
        String id = personService.createPerson(
                "Muster", "Max", Geschlecht.MAENNLICH,
                LocalDate.of(1978, 5, 12), "756.1234.5678.97");
        personService.addAdresse(id, AdressTyp.WOHNADRESSE,
                "Musterstrasse", "10", "8001", "Zürich", "Schweiz",
                LocalDate.of(2015, 1, 1), null);
        personService.addAdresse(id, AdressTyp.KORRESPONDENZADRESSE,
                "Postfach", "99", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 3, 15), null);
    }

    /**
     * Anna Müller – Adressverlauf: historische Wohnadresse (abgelaufen) + aktuelle.
     */
    private void seedAnnaMueller() {
        String id = personService.createPerson(
                "Müller", "Anna", Geschlecht.WEIBLICH,
                LocalDate.of(1990, 9, 3), "756.5432.1987.61");
        // historisch zuerst einfügen, damit keine auto-Anpassung nötig
        personService.addAdresse(id, AdressTyp.WOHNADRESSE,
                "Altgasse", "12", "4000", "Basel", "Schweiz",
                LocalDate.of(2010, 1, 1), LocalDate.of(2018, 5, 31));
        personService.addAdresse(id, AdressTyp.WOHNADRESSE,
                "Bahnhofstrasse", "5", "3000", "Bern", "Schweiz",
                LocalDate.of(2018, 6, 1), null);
    }

    /**
     * Hans Meier – aktuelle Wohnadresse (aktuell) + vorerfasste Zukünftige (vorerfasst).
     * Die aktuelle wird automatisch auf 2026-05-31 zugeschnitten.
     */
    private void seedHansMeier() {
        String id = personService.createPerson(
                "Meier", "Hans", Geschlecht.MAENNLICH,
                LocalDate.of(1965, 3, 22), "756.7654.3219.89");
        // aktuelle zuerst – wird beim Hinzufügen der vorerfassten automatisch geclippt
        personService.addAdresse(id, AdressTyp.WOHNADRESSE,
                "Hauptstrasse", "7", "6000", "Luzern", "Schweiz",
                LocalDate.of(2019, 1, 1), null);
        // vorerfasst: löst auto-Clip der Luzern-Adresse auf 2026-05-31 aus
        personService.addAdresse(id, AdressTyp.WOHNADRESSE,
                "Neugasse", "3", "6300", "Zug", "Schweiz",
                LocalDate.of(2026, 6, 1), null);
        personService.addAdresse(id, AdressTyp.ZUSTELLADRESSE,
                "Lieferweg", "1", "6000", "Luzern", "Schweiz",
                LocalDate.of(2023, 1, 1), null);
    }

    /**
     * Maria Braun – einfache aktuelle Wohnadresse ohne Geschichte.
     */
    private void seedMariaBraun() {
        String id = personService.createPerson(
                "Braun", "Maria", Geschlecht.WEIBLICH,
                LocalDate.of(1985, 11, 8), "756.8765.4321.51");
        personService.addAdresse(id, AdressTyp.WOHNADRESSE,
                "Rosengasse", "4", "9000", "St. Gallen", "Schweiz",
                LocalDate.of(2021, 4, 1), null);
    }

    /**
     * Peter Schmid – WOHNADRESSE aktuell + KORRESPONDENZADRESSE mit Verlauf (abgelaufen → aktuell).
     */
    private void seedPeterSchmid() {
        String id = personService.createPerson(
                "Schmid", "Peter", Geschlecht.MAENNLICH,
                LocalDate.of(2000, 7, 15), "756.9876.5432.00");
        personService.addAdresse(id, AdressTyp.WOHNADRESSE,
                "Industriestrasse", "22", "8400", "Winterthur", "Schweiz",
                LocalDate.of(2022, 9, 1), null);
        // abgelaufene Korrespondenzadresse zuerst, dann aktuelle (aneinanderliegend → kein Overlap)
        personService.addAdresse(id, AdressTyp.KORRESPONDENZADRESSE,
                "Alte Adresse", "1", "8400", "Winterthur", "Schweiz",
                LocalDate.of(2022, 9, 1), LocalDate.of(2024, 12, 31));
        personService.addAdresse(id, AdressTyp.KORRESPONDENZADRESSE,
                "Neue Adresse", "2", "8400", "Winterthur", "Schweiz",
                LocalDate.of(2025, 1, 1), null);
    }
}
