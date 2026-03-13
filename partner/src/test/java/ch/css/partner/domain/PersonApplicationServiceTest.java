package ch.css.partner.domain;

import ch.css.partner.domain.model.Adresse;
import ch.css.partner.domain.model.*;
import ch.css.partner.domain.port.out.PersonEventPublisher;
import ch.css.partner.domain.port.out.PersonRepository;
import ch.css.partner.domain.service.PersonApplicationService;
import ch.css.partner.domain.service.PersonNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PersonApplicationService – Use Case Tests")
class PersonApplicationServiceTest {

    @Mock
    PersonRepository personRepository;

    @Mock
    PersonEventPublisher personEventPublisher;

    @InjectMocks
    PersonApplicationService service;

    private static final String AHV_RAW = "756.1234.5678.97";
    private static final AhvNummer AHV = new AhvNummer(AHV_RAW);
    private static final LocalDate GEBURTSDATUM = LocalDate.of(1980, 5, 12);

    private Person testPerson;

    @BeforeEach
    void setUp() {
        testPerson = new Person("Muster", "Hans", Geschlecht.MAENNLICH, GEBURTSDATUM, AHV);
    }

    // ── createPerson ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("createPerson – Person wird gespeichert und Event publiziert")
    void createPerson_savesAndPublishesEvent() {
        when(personRepository.existsByAhvNummer(AHV)).thenReturn(false);
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String id = service.createPerson("Muster", "Hans", Geschlecht.MAENNLICH, GEBURTSDATUM, AHV_RAW);

        assertNotNull(id);
        verify(personRepository).save(any(Person.class));
        verify(personEventPublisher).publishPersonErstellt(anyString(), eq("Muster"), eq("Hans"),
                any(AhvNummer.class), eq(GEBURTSDATUM));
    }

    @Test
    @DisplayName("createPerson – doppelte AHV-Nummer → IllegalArgumentException")
    void createPerson_duplicateAhv_throws() {
        when(personRepository.existsByAhvNummer(AHV)).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.createPerson("Muster", "Hans", Geschlecht.MAENNLICH, GEBURTSDATUM, AHV_RAW));

        verify(personRepository, never()).save(any());
        verify(personEventPublisher, never()).publishPersonErstellt(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("createPerson ohne AHV-Nummer – wird akzeptiert, kein AHV-Check")
    void createPerson_withoutAhv_succeeds() {
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String id = service.createPerson("Muster", "Hans", Geschlecht.MAENNLICH, GEBURTSDATUM, null);

        assertNotNull(id);
        verify(personRepository, never()).existsByAhvNummer(any());
        verify(personRepository).save(any(Person.class));
    }

    @Test
    @DisplayName("createPerson – ungültige AHV-Nummer → IllegalArgumentException")
    void createPerson_invalidAhv_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createPerson("Muster", "Hans", Geschlecht.MAENNLICH, GEBURTSDATUM,
                        "756.0000.0000.00"));
    }

    // ── updatePersonalien ─────────────────────────────────────────────────────

    @Test
    @DisplayName("updatePersonalien – Personalien werden aktualisiert und Event publiziert")
    void updatePersonalien_updatesAndPublishes() {
        when(personRepository.findById(testPerson.getPersonId())).thenReturn(Optional.of(testPerson));
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updatePersonalien(testPerson.getPersonId(), "Neuer", "Name",
                Geschlecht.WEIBLICH, LocalDate.of(1990, 1, 1));

        assertEquals("Neuer", testPerson.getName());
        verify(personEventPublisher).publishPersonAktualisiert(testPerson.getPersonId(), "Neuer", "Name");
    }

    @Test
    @DisplayName("updatePersonalien – Person nicht gefunden → PersonNotFoundException")
    void updatePersonalien_notFound_throws() {
        when(personRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThrows(PersonNotFoundException.class,
                () -> service.updatePersonalien("unknown", "Neuer", "Name",
                        Geschlecht.MAENNLICH, LocalDate.of(1980, 1, 1)));
    }

    // ── deletePerson ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("deletePerson – Person wird gelöscht und Event publiziert")
    void deletePerson_deletesAndPublishes() {
        when(personRepository.findById(testPerson.getPersonId())).thenReturn(Optional.of(testPerson));

        service.deletePerson(testPerson.getPersonId());

        verify(personRepository).delete(testPerson.getPersonId());
        verify(personEventPublisher).publishPersonGeloescht(testPerson.getPersonId());
    }

    @Test
    @DisplayName("deletePerson – Person nicht gefunden → PersonNotFoundException")
    void deletePerson_notFound_throws() {
        when(personRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThrows(PersonNotFoundException.class, () -> service.deletePerson("unknown"));
    }

    // ── searchPersonen ────────────────────────────────────────────────────────

    @Test
    @DisplayName("searchPersonen – kein Suchfeld → IllegalArgumentException")
    void searchPersonen_noFilter_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.searchPersonen(null, null, null, null));
    }

    @Test
    @DisplayName("searchPersonen – mit Name → delegiert an Repository")
    void searchPersonen_byName_delegates() {
        when(personRepository.search("Muster", null, null, null))
                .thenReturn(List.of(testPerson));

        List<Person> result = service.searchPersonen("Muster", null, null, null);
        assertEquals(1, result.size());
    }

    // ── addAdresse ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("addAdresse – Adresse wird hinzugefügt und Event publiziert")
    void addAdresse_addsAndPublishes() {
        when(personRepository.findById(testPerson.getPersonId())).thenReturn(Optional.of(testPerson));
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String adressId = service.addAdresse(
                testPerson.getPersonId(), AdressTyp.WOHNADRESSE,
                "Musterstr.", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);

        assertNotNull(adressId);
        assertEquals(1, testPerson.getAdressen().size());
        verify(personEventPublisher).publishAdresseHinzugefuegt(
                eq(testPerson.getPersonId()), anyString(),
                eq(AdressTyp.WOHNADRESSE), eq(LocalDate.of(2020, 1, 1)));
    }

    @Test
    @DisplayName("addAdresse – Überschneidung → erste Adresse wird automatisch zugeschnitten")
    void addAdresse_overlap_autoAdjusts() {
        when(personRepository.findById(testPerson.getPersonId())).thenReturn(Optional.of(testPerson));
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Erste Adresse: 2020-01-01 – ∞
        service.addAdresse(testPerson.getPersonId(), AdressTyp.WOHNADRESSE,
                "Str", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);

        // Zweite Adresse: 2021-01-01 – ∞ → erste wird auf 2020-12-31 zugeschnitten
        service.addAdresse(testPerson.getPersonId(), AdressTyp.WOHNADRESSE,
                "Str", "2", "3000", "Bern", "Schweiz",
                LocalDate.of(2021, 1, 1), null);

        assertEquals(2, testPerson.getAdressen().size());
        Adresse erste = testPerson.getAdressen().stream()
                .filter(a -> a.getHausnummer().equals("1")).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2020, 12, 31), erste.getGueltigBis());
    }

    // ── updateAdressGueltigkeit ───────────────────────────────────────────────

    @Test
    @DisplayName("updateAdressGueltigkeit – Gültigkeit wird aktualisiert und Event publiziert")
    void updateAdressGueltigkeit_updatesAndPublishes() {
        // Setup: add an address first
        testPerson.addAdresse(AdressTyp.WOHNADRESSE, "Str", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);
        String adressId = testPerson.getAdressen().get(0).getAdressId();

        when(personRepository.findById(testPerson.getPersonId())).thenReturn(Optional.of(testPerson));
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateAdressGueltigkeit(testPerson.getPersonId(), adressId,
                LocalDate.of(2020, 1, 1), LocalDate.of(2022, 12, 31));

        assertEquals(LocalDate.of(2022, 12, 31),
                testPerson.getAdressen().get(0).getGueltigBis());
        verify(personEventPublisher).publishAdresseAktualisiert(
                testPerson.getPersonId(), adressId,
                LocalDate.of(2020, 1, 1), LocalDate.of(2022, 12, 31));
    }

    // ── deleteAdresse ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteAdresse – Adresse wird entfernt")
    void deleteAdresse_removes() {
        testPerson.addAdresse(AdressTyp.WOHNADRESSE, "Str", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);
        String adressId = testPerson.getAdressen().get(0).getAdressId();

        when(personRepository.findById(testPerson.getPersonId())).thenReturn(Optional.of(testPerson));
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deleteAdresse(testPerson.getPersonId(), adressId);

        assertTrue(testPerson.getAdressen().isEmpty());
    }
}

