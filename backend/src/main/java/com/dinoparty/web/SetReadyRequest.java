package com.dinoparty.web;

import jakarta.validation.constraints.NotNull;

record SetReadyRequest(@NotNull Boolean ready) {}
