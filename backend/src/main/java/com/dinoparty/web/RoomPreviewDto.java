package com.dinoparty.web;

import com.dinoparty.room.RoomPreview;

record RoomPreviewDto(
    String code,
    String gameKey,
    String status,
    int playerCount,
    int maxPlayers,
    String hostNickname) {

  static RoomPreviewDto from(RoomPreview preview) {
    return new RoomPreviewDto(
        preview.code(),
        preview.gameKey(),
        preview.status().name(),
        preview.playerCount(),
        preview.maxPlayers(),
        preview.hostNickname());
  }
}
