package com.moviemux.movie.domain.model;
import java.util.List;
/** Zero-based pagination internally, with no extra limit beyond the HTTP contract. */
public record MovieQuery(String title, String genre, String sortJoin, int page, int size, boolean unpaged, List<Order> orders) {
 public MovieQuery { orders=List.copyOf(orders); }
 public record Order(String property, boolean ascending, boolean ignoreCase, NullHandling nullHandling) { }
 public enum NullHandling { NATIVE, NULLS_FIRST, NULLS_LAST }
}
