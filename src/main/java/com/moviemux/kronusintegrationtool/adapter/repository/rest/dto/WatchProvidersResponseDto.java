package com.moviemux.kronusintegrationtool.adapter.repository.rest.dto;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.moviemux.kronusintegrationtool.domain.WatchProviders;
import com.moviemux.kronusintegrationtool.domain.WatchProviders.Country.CountryBuilder;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class WatchProvidersResponseDto {

	private Long id;
	private Map<String, CountryResponseDto> results;

	public WatchProviders toDomain() {
        return com.moviemux.kronusintegrationtool.adapter.repository.rest.mapper.WatchProvidersMapper.toDomain(this);
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@ToString
	public static class CountryResponseDto {
		private String link;
		private List<ProviderResponseDto> flatrate;
		private List<ProviderResponseDto> rent;
		private List<ProviderResponseDto> buy;

		public WatchProviders.Country toDomain() {
            return com.moviemux.kronusintegrationtool.adapter.repository.rest.mapper.WatchProvidersMapper.country(this);
		}
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@ToString
	public static class ProviderResponseDto {
		private String logoPath;
		private Long providerId;
		private String providerName;
		private int displayPriority;

		public WatchProviders.Provider toDomain() {
            return com.moviemux.kronusintegrationtool.adapter.repository.rest.mapper.WatchProvidersMapper.provider(this);
		}
	}

}
