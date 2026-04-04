package ch.yuno.partner.domain;

import ch.yuno.partner.domain.model.*;
import ch.yuno.partner.domain.model.AddressId;
import ch.yuno.partner.domain.model.PersonId;
import ch.yuno.partner.domain.port.out.InsuredNumberGenerator;
import ch.yuno.partner.domain.port.out.PersonEventPublisher;
import ch.yuno.partner.domain.port.out.PersonRepository;
import ch.yuno.partner.domain.port.out.PiiEncryptor;
import ch.yuno.partner.application.PersonCommandService;
import ch.yuno.partner.application.PersonNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    PersonEventPublisher personEventPublisher;

    @Mock
    PiiEncryptor piiEncryptor;

    @Mock
    InsuredNumberGenerator insuredNumberGenerator;

    @InjectMocks
    PersonCommandService service;

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
    @DisplayName("createPerson – Person wird gespeichert und Event publiziert")
    void createPerson_savesAndPublishesEvent() {
        when(personRepository.existsBySocialSecurityNumber(SSN)).thenReturn(false);
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PersonId id = service.createPerson("Muster", "Hans", Gender.MALE, DATE_OF_BIRTH, SSN_RAW);

        assertNotNull(id);
        verify(personRepository).save(any(Person.class));
        verify(personEventPublisher).personCreated(any(Person.class));
    }

    @Test
    @DisplayName("createPerson – doppelte AHV-Nummer → IllegalArgumentException, kein Event")
    void createPerson_duplicateAhv_throws() {
        when(personRepository.existsBySocialSecurityNumber(SSN)).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.createPerson("Muster", "Hans", Gender.MALE, DATE_OF_BIRTH, SSN_RAW));

        verify(personRepository, never()).save(any());
        verifyNoInteractions(personEventPublisher);
    }

    @Test
    @DisplayName("createPerson ohne AHV-Nummer – wird akzeptiert, kein AHV-Check")
    void createPerson_withoutAhv_succeeds() {
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PersonId id = service.createPerson("Muster", "Hans", Gender.MALE, DATE_OF_BIRTH, null);

        assertNotNull(id);
        verify(personRepository, never()).existsBySocialSecurityNumber(any());
        verify(personRepository).save(any(Person.class));
        verify(personEventPublisher).personCreated(any(Person.class));
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
    @DisplayName("updatePersonalData – Personalien werden aktualisiert und Event publiziert")
    void updatePersonalData_updatesAndPublishesEvent() {
        when(personRepository.findById(testPerson.getPersonId())).thenReturn(Optional.of(testPerson));
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updatePersonalData(testPerson.getPersonId(), "Neuer", "Name",
                Gender.FEMALE, LocalDate.of(1990, 1, 1));

        assertEquals("Neuer", testPerson.getName());
        verify(personEventPublisher).personUpdated(any(Person.class));
    }

    @Test
    @DisplayName("updatePersonalData – Person nicht gefunden → PersonNotFoundException")
    void updatePersonalData_notFound_throws() {
        when(personRepository.findById(PersonId.of("unknown"))).thenReturn(Optional.empty());

        assertThrows(PersonNotFoundException.class,
                () -> service.updatePersonalData(PersonId.of("unknown"), "Neuer", "Name",
                        Gender.MALE, LocalDate.of(1980, 1, 1)));
    }

    // ── deletePerson ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("deletePerson – Person wird gelöscht, Event publiziert und Vault-Key gelöscht (ADR-009)")
    void deletePerson_deletesAndPublishesEvent() {
        when(personRepository.findById(testPerson.getPersonId())).thenReturn(Optional.of(testPerson));

        service.deletePerson(testPerson.getPersonId());

        verify(personRepository).delete(testPerson.getPersonId());
        verify(personEventPublisher).personDeleted(testPerson.getPersonId());
        // ADR-009: Verify crypto-shredding — Vault key must be deleted
        verify(piiEncryptor).deleteKey(testPerson.getPersonId().value());
    }

    @Test
    @DisplayName("deletePerson – Person nicht gefunden → PersonNotFoundException")
    void deletePerson_notFound_throws() {
        when(personRepository.findById(PersonId.of("unknown"))).thenReturn(Optional.empty());

        assertThrows(PersonNotFoundException.class, () -> service.deletePerson(PersonId.of("unknown")));
    }

    // ── addAddress ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("addAddress – Adresse wird hinzugefügt und Event publiziert")
    void addAddress_addsAndPublishesEvent() {
        when(personRepository.findById(testPerson.getPersonId())).thenReturn(Optional.of(testPerson));
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AddressId addressId = service.addAddress(
                testPerson.getPersonId(), AddressType.RESIDENCE,
                "Musterstr.", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);

        assertNotNull(addressId);
        assertEquals(1, testPerson.getAddresses().size());
        verify(personEventPublisher).addressAdded(any(Person.class), any(AddressId.class),
                eq(AddressType.RESIDENCE), eq("Musterstr."), eq("1"), eq("8001"),
                eq("Zürich"), eq("Schweiz"), eq(LocalDate.of(2020, 1, 1)), isNull());
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
    @DisplayName("updateAddressValidity – Gültigkeit wird aktualisiert und Event publiziert")
    void updateAddressValidity_updatesAndPublishesEvent() {
        testPerson.addAddress(AddressType.RESIDENCE, "Str", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);
        AddressId addressId = testPerson.getAddresses().get(0).getAddressId();

        when(personRepository.findById(testPerson.getPersonId())).thenReturn(Optional.of(testPerson));
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateAddressValidity(testPerson.getPersonId(), addressId,
                LocalDate.of(2020, 1, 1), LocalDate.of(2022, 12, 31));

        assertEquals(LocalDate.of(2022, 12, 31),
                testPerson.getAddresses().get(0).getValidTo());
        verify(personEventPublisher).addressUpdated(any(Person.class), any(AddressId.class),
                any(LocalDate.class), any(LocalDate.class));
    }

    // ── deleteAddress ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteAddress – Adresse wird entfernt, stateChanged publiziert")
    void deleteAddress_removes() {
        testPerson.addAddress(AddressType.RESIDENCE, "Str", "1", "8001", "Zürich", "Schweiz",
                LocalDate.of(2020, 1, 1), null);
        AddressId addressId = testPerson.getAddresses().get(0).getAddressId();

        when(personRepository.findById(testPerson.getPersonId())).thenReturn(Optional.of(testPerson));
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deleteAddress(testPerson.getPersonId(), addressId);

        assertTrue(testPerson.getAddresses().isEmpty());
        verify(personEventPublisher).stateChanged(any(Person.class));
    }

    // ── assignInsuredNumberIfAbsent ───────────────────────────────────────────

    @Test
    @DisplayName("assignInsuredNumberIfAbsent – Person ohne Nummer → Nummer wird zugewiesen, Event publiziert")
    void assignInsuredNumber_newAssignment() {
        when(personRepository.findById(testPerson.getPersonId())).thenReturn(Optional.of(testPerson));
        when(personRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(insuredNumberGenerator.nextInsuredNumber()).thenReturn(InsuredNumber.fromSequence(42));

        boolean assigned = service.assignInsuredNumberIfAbsent(testPerson.getPersonId());

        assertTrue(assigned);
        assertEquals(new InsuredNumber("VN-00000042"), testPerson.getInsuredNumber());
        assertTrue(testPerson.isInsured());
        verify(personRepository).save(testPerson);
        verify(personEventPublisher).personUpdated(any(Person.class));
    }

    @Test
    @DisplayName("assignInsuredNumberIfAbsent – Person hat bereits Nummer → idempotent, kein Event")
    void assignInsuredNumber_alreadyInsured_skips() {
        testPerson.assignInsuredNumber(InsuredNumber.fromSequence(1));
        when(personRepository.findById(testPerson.getPersonId())).thenReturn(Optional.of(testPerson));

        boolean assigned = service.assignInsuredNumberIfAbsent(testPerson.getPersonId());

        assertFalse(assigned);
        verify(personRepository, never()).save(any());
        verifyNoInteractions(personEventPublisher);
        verify(insuredNumberGenerator, never()).nextInsuredNumber();
    }

    @Test
    @DisplayName("assignInsuredNumberIfAbsent – Person nicht gefunden → PersonNotFoundException")
    void assignInsuredNumber_notFound_throws() {
        when(personRepository.findById(PersonId.of("unknown"))).thenReturn(Optional.empty());

        assertThrows(PersonNotFoundException.class,
                () -> service.assignInsuredNumberIfAbsent(PersonId.of("unknown")));
    }
}
