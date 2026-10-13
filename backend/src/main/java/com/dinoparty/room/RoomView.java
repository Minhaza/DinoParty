package com.dinoparty.room;

/** What a caller may see of a room: full details for members, a preview for everyone else. */
public sealed interface RoomView permits RoomDetails, RoomPreview {}
