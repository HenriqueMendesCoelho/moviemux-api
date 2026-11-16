package com.moviemux.user.domain.command;

public record RedeemPasswordResetCommand(String key, String email, String password) {}
