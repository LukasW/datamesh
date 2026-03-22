package ch.yuno.partner.domain.model;

public record PageRequest(int page, int size) {
    public PageRequest {
        if (page < 0) throw new IllegalArgumentException("Page must be >= 0");
        if (size < 1 || size > 100) throw new IllegalArgumentException("Size must be between 1 and 100");
    }
}
