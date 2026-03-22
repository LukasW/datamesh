package ch.yuno.partner.domain.service;

import ch.yuno.partner.domain.model.AddressType;

import java.time.LocalDate;

public class AddressOverlapException extends RuntimeException {
    public AddressOverlapException(AddressType addressType, LocalDate fromNew, LocalDate toNew,
                                   LocalDate fromExisting, LocalDate toExisting) {
        super(String.format(
                "An address of type %s already exists in the period %s – %s, " +
                "which overlaps with %s – %s",
                addressType,
                fromExisting, toExisting != null ? toExisting : "∞",
                fromNew, toNew != null ? toNew : "∞"
        ));
    }
}
