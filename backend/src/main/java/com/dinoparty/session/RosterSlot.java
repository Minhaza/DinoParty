package com.dinoparty.session;

import com.dinoparty.engine.PlayerInfo;
import com.dinoparty.engine.PlayerStatus;

/** A roster member. The slot is never removed, so play-order indexes stay stable. */
final class RosterSlot {

  final PlayerInfo info;
  PlayerStatus status;

  RosterSlot(PlayerInfo info, PlayerStatus status) {
    this.info = info;
    this.status = status;
  }
}
