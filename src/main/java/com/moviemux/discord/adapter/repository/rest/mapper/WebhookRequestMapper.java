package com.moviemux.discord.adapter.repository.rest.mapper;

import com.moviemux.discord.adapter.repository.rest.dto.DiscordWebhookRequestDto;
import com.moviemux.discord.adapter.repository.rest.dto.EmbedRequestDto;
import com.moviemux.discord.domain.model.WebhookMovie;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class WebhookRequestMapper {
    private WebhookRequestMapper() { }
    public static DiscordWebhookRequestDto toRequest(WebhookMovie movie, String roleId) {
        List<EmbedRequestDto.Field> fields = movie.ratings() == null ? new ArrayList<>() : movie.ratings().stream()
                .map(note -> new EmbedRequestDto.Field(note.userName(), Objects.toString(note.note(), ""), true))
                .sorted(EmbedRequestDto.Field.comparator()).toList();
        String title = movie.showNotes() ? "%s | Nota: %s".formatted(movie.title(), average(movie)) : movie.title();
        String footer = movie.registeredBy() == null ? "🔴 Cadastrado por: Usuário desconhecido"
                : "Cadastrado por: %s".formatted(movie.registeredBy());
        var embed = new EmbedRequestDto(title, new EmbedRequestDto.Image(movie.imageUrl()), 3447003,
                "https://www.moviemux.com/movie/" + movie.id(), new EmbedRequestDto.Footer(footer),
                fields, movie.createdAt());
        return new DiscordWebhookRequestDto(roleId == null ? "" : "<@&%s>".formatted(roleId), List.of(embed));
    }
    private static String average(WebhookMovie movie) {
        if (movie.ratings() == null) return "0.0";
        double average = movie.ratings().stream().mapToDouble(rating -> rating.note()).average().orElse(0.0);
        var format = new DecimalFormat("0.0", new DecimalFormatSymbols(Locale.US));
        format.setRoundingMode(RoundingMode.HALF_EVEN);
        return format.format(average);
    }
}
