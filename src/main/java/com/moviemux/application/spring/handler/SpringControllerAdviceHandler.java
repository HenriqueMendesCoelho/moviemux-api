package com.moviemux.application.spring.handler;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.moviemux.adapter.util.exception.TokenInvalidException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice
@Slf4j
public class SpringControllerAdviceHandler {

	@ResponseStatus(HttpStatus.BAD_REQUEST)
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public Map<String, Object> handleValidationExceptions(MethodArgumentNotValidException ex,
			HttpServletRequest request) {
		log.error("Request: " + request.getRequestURI() + " raised: ", ex);

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("timestamp", new Date(System.currentTimeMillis()).toString());
		response.put("status", 400);
		response.put("error", "Bad Request");
		response.put("path", request.getRequestURI());

		Map<String, Object> errors = new LinkedHashMap<>();
		ex.getBindingResult().getAllErrors().forEach((error) -> {
			String fieldName = ((FieldError) error).getField();
			String errorMessage = error.getDefaultMessage();
			errors.put(fieldName, errorMessage);
		});

		response.put("errors", errors);

		return response;
	}

	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	@ExceptionHandler(TokenInvalidException.class)
	public Map<String, Object> handleValidationExceptions(TokenInvalidException ex, HttpServletRequest request) {
		log.error("Request: " + request.getRequestURI() + " raised: ", ex);

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("timestamp", new Date(System.currentTimeMillis()).toString());
		response.put("status", HttpStatus.UNAUTHORIZED.value());
		response.put("error", "Unauthorized");
		response.put("message", ex.getMessage());
		response.put("path", request.getRequestURI());

		return response;
	}

	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	@ExceptionHandler(Exception.class)
	public Map<String, Object> handleError(HttpServletRequest request, Exception ex) {
		log.error("Request: " + request.getRequestURI() + " raised: ", ex);

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("timestamp", new Date(System.currentTimeMillis()).toString());
		response.put("status", "500");
		response.put("error", "internal server error");
		response.put("message", "contact the administrator");
		response.put("path", request.getRequestURI());

		return response;
	}
}
