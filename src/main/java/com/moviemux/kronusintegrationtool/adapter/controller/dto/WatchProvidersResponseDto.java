package com.moviemux.kronusintegrationtool.adapter.controller.dto;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.moviemux.kronusintegrationtool.domain.model.WatchProviders;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WatchProvidersResponseDto {

	private Long id;
	private Map<String, CountryResponseDto> results;

	public WatchProvidersResponseDto(WatchProviders watch) {
		id = watch.getId();
		results = watch.getResults()
				.entrySet()
				.stream()
				.collect(Collectors.toMap(Map.Entry::getKey,
						r -> new WatchProvidersResponseDto.CountryResponseDto(r.getValue())));
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class CountryResponseDto {
		private String link;
		private List<ProviderResponseDto> flatrate;
		private List<ProviderResponseDto> rent;
		private List<ProviderResponseDto> buy;

		public CountryResponseDto(WatchProviders.Country country) {
			link = country.getLink();
			if (country.getFlatrate() != null) {
				flatrate = country.getFlatrate()
						.stream()
						.map(WatchProvidersResponseDto.ProviderResponseDto::new)
						.collect(Collectors.toList());
			}
			if (country.getRent() != null) {
				rent = country.getRent()
						.stream()
						.map(WatchProvidersResponseDto.ProviderResponseDto::new)
						.collect(Collectors.toList());
			}
			if (country.getBuy() != null) {
				buy = country.getBuy()
						.stream()
						.map(WatchProvidersResponseDto.ProviderResponseDto::new)
						.collect(Collectors.toList());
			}
		}
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ProviderResponseDto {
		private String logoPath;
		private Long providerId;
		private String providerName;
		private int displayPriority;

		public ProviderResponseDto(WatchProviders.Provider provider) {
			logoPath = provider.getLogoPath();
			providerId = provider.getProviderId();
			providerName = provider.getProviderName();
			displayPriority = provider.getDisplayPriority();
		}
	}

}
