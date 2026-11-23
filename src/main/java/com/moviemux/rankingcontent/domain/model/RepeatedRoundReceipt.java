package com.moviemux.rankingcontent.domain.model;

import java.util.UUID;

public record RepeatedRoundReceipt(UUID roundId, long roundNumber, boolean created) {}
