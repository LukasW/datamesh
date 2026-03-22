package ch.yuno.product.domain.model;

import java.util.List;

public record PageResult<T>(List<T> content, long totalElements, int totalPages) {}
