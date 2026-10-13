package com.dinoparty.room;

import java.util.UUID;

/**
 * Optional view of live connection state, implemented by the realtime module, so host transfer can
 * prefer players who are actually connected without the room module knowing about sockets.
 */
public interface MemberPresence {

  boolean isDisconnected(String roomCode, UUID guestId);
}
