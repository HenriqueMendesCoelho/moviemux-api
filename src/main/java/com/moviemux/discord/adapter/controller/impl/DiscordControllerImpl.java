package com.moviemux.discord.adapter.controller.impl;

import java.util.UUID;

import com.moviemux.discord.adapter.controller.DiscordController;
import com.moviemux.legacyboard.domain.repository.LegacyBoardInterlock;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

import com.moviemux.discord.domain.usecase.SendMessageWebhookUseCase;
import com.moviemux.discord.domain.usecase.UpdateMessageWebhookUseCase;

@Controller
public class DiscordControllerImpl implements DiscordController {

	private final LegacyBoardInterlock legacyBoard;

	private final SendMessageWebhookUseCase sendMessageWebhook;

	private final UpdateMessageWebhookUseCase updateMessageWebhook;

	public DiscordControllerImpl(SendMessageWebhookUseCase sendMessageWebhook, UpdateMessageWebhookUseCase updateMessageWebhook,
			LegacyBoardInterlock legacyBoard) {
		this.legacyBoard = legacyBoard;
		this.sendMessageWebhook = sendMessageWebhook;
		this.updateMessageWebhook = updateMessageWebhook;
	}

	@Override
	@Transactional
	public void execute(UUID movieId) {
		legacyBoard.requireLegacy();
		sendMessageWebhook.execute(movieId);
	}

	@Override
	@Transactional
	public void update(UUID movieId) {
		legacyBoard.requireLegacy();
		updateMessageWebhook.execute(movieId);
	}

}
