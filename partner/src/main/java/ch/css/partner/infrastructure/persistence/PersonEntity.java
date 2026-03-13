package ch.css.partner.infrastructure.persistence;

import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "person")
@Audited
public class PersonEntity {

    @Id
    @Column(name = "person_id", length = 36)
    private String personId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "vorname", nullable = false, length = 100)
    private String vorname;

    @Column(name = "geschlecht", nullable = false, length = 10)
    private String geschlecht;

    @Column(name = "geburtsdatum", nullable = false)
    private LocalDate geburtsdatum;

    @Column(name = "ahv_nummer", nullable = true, unique = true, length = 16)
    private String ahvNummer;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "person", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Audited
    private List<AdresseEntity> adressen = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters & Setters
    public String getPersonId() { return personId; }
    public void setPersonId(String personId) { this.personId = personId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getVorname() { return vorname; }
    public void setVorname(String vorname) { this.vorname = vorname; }
    public String getGeschlecht() { return geschlecht; }
    public void setGeschlecht(String geschlecht) { this.geschlecht = geschlecht; }
    public LocalDate getGeburtsdatum() { return geburtsdatum; }
    public void setGeburtsdatum(LocalDate geburtsdatum) { this.geburtsdatum = geburtsdatum; }
    public String getAhvNummer() { return ahvNummer; }
    public void setAhvNummer(String ahvNummer) { this.ahvNummer = ahvNummer; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public List<AdresseEntity> getAdressen() { return adressen; }
    public void setAdressen(List<AdresseEntity> adressen) { this.adressen = adressen; }
}
