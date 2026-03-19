package ch.css.partner.domain.model.events;

import java.time.LocalDate;

public record AddressUpdatedEvent(
    String personId,
    String addressId,
    LocalDate validFrom,
    LocalDate validTo
) {}
