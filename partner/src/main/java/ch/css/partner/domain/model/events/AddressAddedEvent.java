package ch.css.partner.domain.model.events;

import java.time.LocalDate;

public record AddressAddedEvent(
    String personId,
    String addressId,
    String addressType,
    String street,
    String houseNumber,
    String postalCode,
    String city,
    String land,
    LocalDate validFrom,
    LocalDate validTo
) {}
