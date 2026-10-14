package com.dinoparty.engine;

import java.util.UUID;

public record DeadlineFired(String id, UUID phaseId, long generation) {}
