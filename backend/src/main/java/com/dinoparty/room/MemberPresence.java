package com.dinoparty.room;

import java.util.UUID;

/**
 * Optional view of live connection state, implemented by the realtime module, so host transfer can
 * prefer players who are actually connected without the room module knowing about sockets.
 */
public interface MemberPresence {

  boolean isDisconnected(String roomCode, UUID guestId);

  /**
   * True while the guest has an open connection, or lost it and is still inside the disconnect
   * grace period of this room; such a member is never treated as gone.
   */
  boolean isLive(String roomCode, UUID guestId);
}
