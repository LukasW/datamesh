package ch.css.partner.infrastructure.persistence;

import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "adresse")
@Audited
public class AdresseEntity {

    @Id
    @Column(name = "adress_id", length = 36)
    private String adressId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_id", nullable = false)
    private PersonEntity person;

    @Column(name = "adress_typ", nullable = false, length = 25)
    private String adressTyp;

    @Column(name = "strasse", nullable = false, length = 255)
    private String strasse;

    @Column(name = "hausnummer", nullable = false, length = 20)
    private String hausnummer;

    @Column(name = "plz", nullable = false, length = 4)
    private String plz;

    @Column(name = "ort", nullable = false, length = 100)
    private String ort;

    @Column(name = "land", nullable = false, length = 100)
    private String land;

    @Column(name = "gueltig_von", nullable = false)
    private LocalDate gueltigVon;

    @Column(name = "gueltig_bis")
    private LocalDate gueltigBis;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // Getters & Setters
    public String getAdressId() { return adressId; }
    public void setAdressId(String adressId) { this.adressId = adressId; }
    public PersonEntity getPerson() { return person; }
    public void setPerson(PersonEntity person) { this.person = person; }
    public String getAdressTyp() { return adressTyp; }
    public void setAdressTyp(String adressTyp) { this.adressTyp = adressTyp; }
    public String getStrasse() { return strasse; }
    public void setStrasse(String strasse) { this.strasse = strasse; }
    public String getHausnummer() { return hausnummer; }
    public void setHausnummer(String hausnummer) { this.hausnummer = hausnummer; }
    public String getPlz() { return plz; }
    public void setPlz(String plz) { this.plz = plz; }
    public String getOrt() { return ort; }
    public void setOrt(String ort) { this.ort = ort; }
    public String getLand() { return land; }
    public void setLand(String land) { this.land = land; }
    public LocalDate getGueltigVon() { return gueltigVon; }
    public void setGueltigVon(LocalDate gueltigVon) { this.gueltigVon = gueltigVon; }
    public LocalDate getGueltigBis() { return gueltigBis; }
    public void setGueltigBis(LocalDate gueltigBis) { this.gueltigBis = gueltigBis; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
