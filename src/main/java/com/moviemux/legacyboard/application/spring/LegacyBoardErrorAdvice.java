package com.moviemux.legacyboard.application.spring;

import com.moviemux.legacyboard.domain.exception.LegacyBoardRetiredException;
import com.moviemux.legacyboard.domain.exception.LegacyBoardUnavailableException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class LegacyBoardErrorAdvice {
  @ExceptionHandler(LegacyBoardRetiredException.class)
  public ResponseEntity<Map<String, String>> retired() {
    return error(HttpStatus.GONE, "movie.legacy_board_retired");
  }

  @ExceptionHandler(LegacyBoardUnavailableException.class)
  public ResponseEntity<Map<String, String>> unavailable() {
    return error(HttpStatus.SERVICE_UNAVAILABLE, "movie.legacy_board_unavailable");
  }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
  }
}
