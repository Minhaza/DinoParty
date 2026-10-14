package com.dinoparty.web;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

record KickRequest(@NotNull UUID guestId) {}
