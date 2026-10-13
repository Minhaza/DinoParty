package com.dinoparty.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Map;

record UpdateRoomSettingsRequest(
    @Size(max = 40) String gameKey,
    @Size(max = 20) Map<String, Object> settings,
    @Min(1) @Max(64) Integer maxPlayers) {}
