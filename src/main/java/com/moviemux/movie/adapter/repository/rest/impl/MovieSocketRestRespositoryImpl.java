package com.moviemux.movie.adapter.repository.rest.impl;

import com.moviemux.movie.adapter.repository.rest.mapper.NotificationMapper;
import com.moviemux.movie.domain.model.NotificationNote;
import com.moviemux.movie.domain.repository.MovieNotificationRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviemux.movie.adapter.repository.rest.MovieSocketRespository;
import com.moviemux.movie.adapter.repository.rest.dto.MovieNoteRestRequestDto;
import com.moviemux.movie.domain.MovieNote;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import com.moviemux.movie.domain.exception.NotificationFailure;
import org.springframework.stereotype.Repository;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Repository
@Slf4j
public class MovieSocketRestRespositoryImpl implements MovieSocketRespository, MovieNotificationRepository {

	@Autowired
	private WebClient webClientSocketApi;

	@Autowired
	private ObjectMapper mapper;

	@Override
	public void emitAllMoviesEvent(String event) { notifyAllMovies(event); }

    @Override
    public void notifyAllMovies(String event) {
		try {
			if (StringUtils.isBlank(event)) {
				event = "update";
			}

			webClientSocketApi.post()
					.uri("/api/v1/movie/all/emit/%s".formatted(event))
					.bodyValue(mapper.writeValueAsString(Map.of("event", event)))
					.retrieve()
					.bodyToMono(Void.class)
					.block();
		} catch (Exception e) {
			log.error("Error on Socket Api emit event all movies:", e);
			throw new NotificationFailure(e);
		}
	}

	@Override
	public void emitEventMovie(UUID movieId, String event, MovieNote note, String emmitedByUserId) {
        if (StringUtils.isBlank(event) || movieId == null) return;
        notifyMovie(movieId, event, NotificationMapper.snapshot(note), emmitedByUserId);
    }

    @Override
    public Delivery notifyMovie(UUID movieId, String event, NotificationNote note, String emmitedByUserId) {
		try {
			if (StringUtils.isBlank(event) || movieId == null) {
				return Delivery.SKIPPED;
			}

			Map<String, Object> map = new HashMap<>();
			map.put("event", event);
			map.put("movie", movieId);
			if (note != null) {
				map.put("content", NotificationMapper.toRequest(note));
			}
			if (emmitedByUserId != null) {
				map.put("emmitedByUserId", emmitedByUserId);
			}

			String body = mapper.writeValueAsString(map);
			webClientSocketApi.post()
					.uri("/api/v1/movie/%s/note/emit/%s".formatted(movieId, event))
					.bodyValue(body)
					.retrieve()
					.bodyToMono(Void.class)
					.block();
            return Delivery.SENT;
		} catch (Exception e) {
			log.error("Error on Socket Api emit event movie:", e);
			throw new NotificationFailure(e);
		}
	}

}
