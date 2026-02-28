package com.moviemux.board.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.moviemux.movie.domain.Movie;
import com.moviemux.user.domain.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "board_movie_rating",
		uniqueConstraints = @UniqueConstraint(columnNames = { "board_movie_id", "user_id" }))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BoardMovieRating {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, updatable = false)
	private UUID publicId;

	@ManyToOne
	@JoinColumn(name = "board_movie_id", nullable = false)
	private BoardMovie boardMovie;

	@ManyToOne
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(nullable = false)
	private Integer rating;

	@CreationTimestamp
	@Column(nullable = false, columnDefinition = "timestamp with time zone")
	private OffsetDateTime createdAt;

	@UpdateTimestamp
	@Column(columnDefinition = "timestamp with time zone")
	private OffsetDateTime updatedAt;

	@PrePersist
	public void generatePublicId() {
		if (publicId == null) {
			publicId = UUID.randomUUID();
		}
	}
}
