package ch.css.product.domain.model;

public enum ProductLine {
    HOUSEHOLD_CONTENTS("Hausrat"),
    LIABILITY("Haftpflicht"),
    MOTOR_VEHICLE("Motorfahrzeug"),
    TRAVEL("Reise"),
    LEGAL_EXPENSES("Rechtsschutz");

    private final String label;

    ProductLine(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
