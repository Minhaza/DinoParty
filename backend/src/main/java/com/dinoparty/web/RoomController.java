package com.dinoparty.web;

import com.dinoparty.guest.AuthenticatedGuest;
import com.dinoparty.room.CreateRoomCommand;
import com.dinoparty.room.RoomDetails;
import com.dinoparty.room.RoomPreview;
import com.dinoparty.room.RoomProperties;
import com.dinoparty.room.RoomService;
import com.dinoparty.room.RoomView;
import com.dinoparty.room.UpdateRoomSettingsCommand;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Caller identity always comes from the authenticated principal, never from the request. */
@RestController
@RequestMapping("/api/v1/rooms")
class RoomController {

  private final RoomService roomService;
  private final RoomProperties properties;

  RoomController(RoomService roomService, RoomProperties properties) {
    this.roomService = roomService;
    this.properties = properties;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  RoomDto create(
      @AuthenticationPrincipal AuthenticatedGuest caller,
      @Valid @RequestBody CreateRoomRequest request) {
    RoomDetails room =
        roomService.create(
            caller.guestId(),
            new CreateRoomCommand(request.gameKey(), request.settings(), request.maxPlayers()));
    return RoomDto.from(room, properties.publicUrl());
  }

  @GetMapping("/{roomCode}")
  ResponseEntity<?> get(
      @AuthenticationPrincipal AuthenticatedGuest caller, @PathVariable String roomCode) {
    RoomView view = roomService.get(caller.guestId(), roomCode);
    return switch (view) {
      case RoomDetails details -> ResponseEntity.ok(RoomDto.from(details, properties.publicUrl()));
      case RoomPreview preview -> ResponseEntity.ok(RoomPreviewDto.from(preview));
    };
  }

  @PostMapping("/{roomCode}/join")
  RoomDto join(@AuthenticationPrincipal AuthenticatedGuest caller, @PathVariable String roomCode) {
    return RoomDto.from(roomService.join(caller.guestId(), roomCode), properties.publicUrl());
  }

  @PostMapping("/{roomCode}/leave")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void leave(@AuthenticationPrincipal AuthenticatedGuest caller, @PathVariable String roomCode) {
    roomService.leave(caller.guestId(), roomCode);
  }

  @PatchMapping("/{roomCode}/settings")
  RoomDto updateSettings(
      @AuthenticationPrincipal AuthenticatedGuest caller,
      @PathVariable String roomCode,
      @Valid @RequestBody UpdateRoomSettingsRequest request) {
    RoomDetails room =
        roomService.updateSettings(
            caller.guestId(),
            roomCode,
            new UpdateRoomSettingsCommand(
                request.gameKey(), request.settings(), request.maxPlayers()));
    return RoomDto.from(room, properties.publicUrl());
  }
}
