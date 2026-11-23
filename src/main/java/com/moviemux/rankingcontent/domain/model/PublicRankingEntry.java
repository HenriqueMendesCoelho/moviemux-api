package com.moviemux.rankingcontent.domain.model;

import java.math.BigInteger;
import java.util.UUID;

/** Only disclosed scores contribute to this exact tally. */
public record PublicRankingEntry(UUID entryId, String title, BigInteger total, BigInteger votes) {}
