package com.moviemux.discord.adapter.controller.impl;

import java.util.UUID;

import com.moviemux.discord.adapter.controller.DiscordController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;

import com.moviemux.discord.domain.usecase.SendMessageWebhookUseCase;
import com.moviemux.discord.domain.usecase.UpdateMessageWebhookUseCase;

@Controller
public class DiscordControllerImpl implements DiscordController {

	@Autowired
	private SendMessageWebhookUseCase sendMessageWebhook;

	@Autowired
	private UpdateMessageWebhookUseCase updateMessageWebhook;

	@Override
	public void execute(UUID movieId) {
		sendMessageWebhook.execute(movieId);
	}

	@Override
	public void update(UUID movieId) {
		updateMessageWebhook.execute(movieId);
	}

}
