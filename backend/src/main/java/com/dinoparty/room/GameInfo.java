package com.dinoparty.room;

/** What the room module needs to know about a game: its name and lobby player bounds. */
public record GameInfo(String key, String name, int minPlayers, int maxPlayers) {}
