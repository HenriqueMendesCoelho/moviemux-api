package com.moviemux.board.domain;

import java.util.UUID;

import com.moviemux.user.domain.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "board_permission",
		uniqueConstraints = @UniqueConstraint(columnNames = { "board_id", "user_id", "permission" }))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BoardPermission {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, updatable = false)
	private UUID publicId;

	@ManyToOne
	@JoinColumn(name = "board_id", nullable = false)
	private Board board;

	@ManyToOne
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private BoardPermissionType permission;

	@PrePersist
	public void generatePublicId() {
		if (publicId == null) {
			publicId = UUID.randomUUID();
		}
	}
}
