package com.moviemux.board.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.moviemux.movie.domain.Movie;
import com.moviemux.user.domain.User;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "board_movie", uniqueConstraints = @UniqueConstraint(columnNames = { "board_id", "movie_id" }))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BoardMovie {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, updatable = false)
	private UUID publicId;

	@ManyToOne
	@JoinColumn(name = "board_id", nullable = false)
	private Board board;

	@ManyToOne
	@JoinColumn(name = "movie_id", nullable = false)
	private Movie movie;

	@ManyToOne
	@JoinColumn(name = "added_by_id", nullable = true)
	private User addedBy;

	@CreationTimestamp
	@Column(nullable = false, columnDefinition = "timestamp with time zone")
	private OffsetDateTime addedAt;

	@OneToMany(mappedBy = "boardMovie", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<BoardMovieRating> ratings;

	@PrePersist
	public void generatePublicId() {
		if (publicId == null) {
			publicId = UUID.randomUUID();
		}
	}
}
