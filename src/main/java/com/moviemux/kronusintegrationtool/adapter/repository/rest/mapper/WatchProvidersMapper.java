package com.moviemux.kronusintegrationtool.adapter.repository.rest.mapper;

import com.moviemux.kronusintegrationtool.adapter.repository.rest.dto.WatchProvidersResponseDto;
import com.moviemux.kronusintegrationtool.domain.model.WatchProviders;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class WatchProvidersMapper {
    private WatchProvidersMapper() { }
    public static WatchProviders toDomain(WatchProvidersResponseDto response) {
        Objects.requireNonNull(response, "Missing provider response");
        return WatchProviders.builder().id(response.getId())
                .results(response.getResults().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> country(entry.getValue())))).build();
    }
    public static WatchProviders.Country country(WatchProvidersResponseDto.CountryResponseDto value) {
        return WatchProviders.Country.builder().link(value.getLink()).flatrate(providers(value.getFlatrate()))
                .rent(providers(value.getRent())).buy(providers(value.getBuy())).build();
    }
    private static List<WatchProviders.Provider> providers(List<WatchProvidersResponseDto.ProviderResponseDto> values) {
        return values == null ? null : values.stream().map(WatchProvidersMapper::provider).toList();
    }
    public static WatchProviders.Provider provider(WatchProvidersResponseDto.ProviderResponseDto value) {
        return WatchProviders.Provider.builder().logoPath(value.getLogoPath()).providerId(value.getProviderId())
                .providerName(value.getProviderName()).displayPriority(value.getDisplayPriority()).build();
    }
}
