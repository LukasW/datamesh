package ch.yuno.partner.domain;

import ch.yuno.partner.domain.model.*;
import ch.yuno.partner.domain.port.out.OutboxRepository;
import ch.yuno.partner.domain.port.out.PersonRepository;
import ch.yuno.partner.domain.port.out.PiiEncryptor;
import ch.yuno.partner.domain.service.PersonCommandService;
import ch.yuno.partner.domain.service.PersonNotFoundException;
import ch.yuno.partner.infrastructure.messaging.outbox.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("PersonCommandService – Use Case Tests")
class PersonCommandServiceTest {

    @Mock
    PersonRepository personRepository;

    @Mock
    OutboxRepository outboxRepository;

    @Mock
    PiiEncryptor piiEncryptor;

    @InjectMocks
    PersonCommandService service;

    @Captor
    ArgumentCaptor<OutboxEvent> outboxCaptor;

    private static final String SSN_RAW = "756.1234.5678.97";
    private static final SocialSecurityNumber SSN = new SocialSecurityNumber(SSN_RAW);
    private static final LocalDate DATE_OF_BIRTH = LocalDate.of(1980, 5, 12);

    private Person testPerson;

    @BeforeEach
    void setUp() {
        testPerson = new Person("Muster", "Hans", Gender.MALE, DATE_OF_BIRTH, SSN);
        // PiiEncryptor pass-through: return plaintext unchanged (no Vault in unit tests)
        lenient().when(piiEncryptor.encrypt(any(), any())).thenAnswer(inv -> inv.getArgument(1));
    }

    // ── createPerson ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("createPerson – Person wird gespeichert und Outbox-Event geschrieben")
    void createPerson_savesAndWritesOutboxEvent() {
        when(personRepository.existsBySocialSecurityNumber(SSN)).thenReturn(false);
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String id = service.createPerson("Muster", "Hans", Gender.MALE, DATE_OF_BIRTH, SSN_RAW);

        assertNotNull(id);
        verify(personRepository).save(any(Person.class));
        verify(outboxRepository, times(2)).save(outboxCaptor.capture());
        OutboxEvent event = outboxCaptor.getAllValues().stream()
                .filter(e -> "PersonCreated".equals(e.getEventType())).findFirst().orElseThrow();
        assertEquals("person.v1.created", event.getTopic());
        assertEquals("person", event.getAggregateType());
        assertEquals(id, event.getAggregateId());
        assertNotNull(event.getPayload());
        assertTrue(event.getPayload().contains("\"eventType\":\"PersonCreated\""));
    }

    @Test
    @DisplayName("createPerson – doppelte AHV-Nummer → IllegalArgumentException, kein Outbox-Event")
    void createPerson_duplicateAhv_throws() {
        when(personRepository.existsBySocialSecurityNumber(SSN)).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.createPerson("Muster", "Hans", Gender.MALE, DATE_OF_BIRTH, SSN_RAW));

        verify(personRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    @DisplayName("createPerson ohne AHV-Nummer – wird akzeptiert, kein AHV-Check")
    void createPerson_withoutAhv_succeeds() {
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String id = service.createPerson("Muster", "Hans", Gender.MALE, DATE_OF_BIRTH, null);

        assertNotNull(id);
        verify(personRepository, never()).existsBySocialSecurityNumber(any());
        verify(personRepository).save(any(Person.class));
        verify(outboxRepository, times(2)).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("createPerson – ungültige AHV-Nummer → IllegalArgumentException")
    void createPerson_invalidAhv_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createPerson("Muster", "Hans", Gender.MALE, DATE_OF_BIRTH,
                        "756.0000.0000.00"));
    }

    // ── updatePersonalData ────────────────────────────────────────────────────

    @Test
    @DisplayName("updatePersonalData – Personalien werden aktualisiert und Outbox-Event geschrieben")
    void updatePersonalData_updatesAndWritesOutboxEvent() {
        when(personRepository.findById(testPerson.getPersonId())).thenReturn(Optional.of(testPerson));
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updatePersonalData(testPerson.getPersonId(), "Neuer", "Name",
                Gender.FEMALE, LocalDate.of(1990, 1, 1));

        assertEquals("Neuer", testPerson.getName());
        verify(outboxRepository, times(2)).save(outboxCaptor.capture());
        OutboxEvent event = outboxCaptor.getAllValues().stream()
                .filter(e -> "PersonUpdated".equals(e.getEventType())).findFirst().orElseThrow();
        assertEquals("person.v1.updated", event.getTopic());
        assertTrue(event.getPayload().contains("\"name\":\"Neuer\""));
    }

    @Test
    @DisplayName("updatePersonalData – Person nicht gefunden → PersonNotFoundException")
    void updatePersonalData_notFound_throws() {
        when(personRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThrows(PersonNotFoundException.class,
                () -> service.updatePersonalData("unknown", "Neuer", "Name",
                        Gender.MALE, LocalDate.of(1980, 1, 1)));
    }

    // ── deletePerson ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("deletePerson – Person wird gelöscht, Outbox-Event geschrieben und Vault-Key gelöscht (ADR-009)")
    void deletePerson_deletesAndWritesOutboxEvent() {
        when(personRepository.findById(testPerson.getPersonId())).thenReturn(Optional.of(testPerson));

        service.deletePerson(testPerson.getPersonId());

        verify(personRepository).delete(testPerson.getPersonId());
        verify(outboxRepository, times(2)).save(outboxCaptor.capture());
        OutboxEvent event = outboxCaptor.getAllValues().stream()
                .filter(e -> "PersonDeleted".equals(e.getEventType())).findFirst().orElseThrow();
        assertEquals("person.v1.deleted", event.getTopic());
        // ADR-009: Verify crypto-shredding — Vault key must be deleted
        verify(piiEncryptor).deleteKey(testPerson.getPersonId());
    }

    @Test
    @DisplayName("deletePerson – Person nicht gefunden → PersonNotFoundException")
    void deletePerson_notFound_throws() {
        when(personRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThrows(PersonNotFoundException.class, () -> service.deletePerson("unknown"));
    }

    // ── addAddress ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("addAddress – Adresse wird hinzugefügt und Outbox-Event geschrieben")
    void addAddress_addsAndWritesOutboxEvent() {
        when(personRepository.findById(testPerson.getPersonId())).thenReturn(Optional.of(testPerson));
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String addressId = service.addAddress(
                testPerson.getPersonId(), AddressType.RESIDENCE,
                "Musterstr.", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);

        assertNotNull(addressId);
        assertEquals(1, testPerson.getAddresses().size());
        verify(outboxRepository, times(2)).save(outboxCaptor.capture());
        OutboxEvent event = outboxCaptor.getAllValues().stream()
                .filter(e -> "AddressAdded".equals(e.getEventType())).findFirst().orElseThrow();
        assertEquals("person.v1.address-added", event.getTopic());
        assertTrue(event.getPayload().contains("\"addressType\":\"RESIDENCE\""));
    }

    @Test
    @DisplayName("addAddress – Überschneidung → erste Adresse wird automatisch zugeschnitten")
    void addAddress_overlap_autoAdjusts() {
        when(personRepository.findById(testPerson.getPersonId())).thenReturn(Optional.of(testPerson));
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.addAddress(testPerson.getPersonId(), AddressType.RESIDENCE,
                "Str", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);
        service.addAddress(testPerson.getPersonId(), AddressType.RESIDENCE,
                "Str", "2", "3000", "Bern", "Schweiz",
                LocalDate.of(2021, 1, 1), null);

        assertEquals(2, testPerson.getAddresses().size());
        Address first = testPerson.getAddresses().stream()
                .filter(a -> a.getHouseNumber().equals("1")).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2020, 12, 31), first.getValidTo());
    }

    // ── updateAddressValidity ─────────────────────────────────────────────────

    @Test
    @DisplayName("updateAddressValidity – Gültigkeit wird aktualisiert und Outbox-Event geschrieben")
    void updateAddressValidity_updatesAndWritesOutboxEvent() {
        testPerson.addAddress(AddressType.RESIDENCE, "Str", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);
        String addressId = testPerson.getAddresses().get(0).getAddressId();

        when(personRepository.findById(testPerson.getPersonId())).thenReturn(Optional.of(testPerson));
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateAddressValidity(testPerson.getPersonId(), addressId,
                LocalDate.of(2020, 1, 1), LocalDate.of(2022, 12, 31));

        assertEquals(LocalDate.of(2022, 12, 31),
                testPerson.getAddresses().get(0).getValidTo());
        verify(outboxRepository, times(2)).save(outboxCaptor.capture());
        OutboxEvent event = outboxCaptor.getAllValues().stream()
                .filter(e -> "AddressUpdated".equals(e.getEventType())).findFirst().orElseThrow();
        assertEquals("person.v1.address-updated", event.getTopic());
    }

    // ── deleteAddress ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteAddress – Adresse wird entfernt, kein Outbox-Event")
    void deleteAddress_removes() {
        testPerson.addAddress(AddressType.RESIDENCE, "Str", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);
        String addressId = testPerson.getAddresses().get(0).getAddressId();

        when(personRepository.findById(testPerson.getPersonId())).thenReturn(Optional.of(testPerson));
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deleteAddress(testPerson.getPersonId(), addressId);

        assertTrue(testPerson.getAddresses().isEmpty());
        verify(outboxRepository).save(outboxCaptor.capture());
        assertEquals("PersonState", outboxCaptor.getValue().getEventType());
    }
}
