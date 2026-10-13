package com.dinoparty.realtime;

import com.dinoparty.guest.GuestRegistration;
import com.dinoparty.guest.GuestService;
import com.dinoparty.room.CreateRoomCommand;
import com.dinoparty.room.RoomDetails;
import com.dinoparty.room.RoomService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** Shared setup for the realtime integration tests: guests and rooms via the real services. */
abstract class RealtimeFixture {

  record Guest(UUID id, String token) {}

  @Autowired protected GuestService guestService;
  @Autowired protected RoomService roomService;

  @Value("${local.server.port}")
  protected int port;

  protected final List<StompTestClient> clients = new ArrayList<>();

  protected Guest newGuest() {
    GuestRegistration registration =
        guestService.register(
            "G" + UUID.randomUUID().toString().substring(0, 8), "dino-rex", null, "it");
    return new Guest(registration.guest().id(), registration.token());
  }

  protected RoomDetails newRoom(Guest host) {
    return roomService.create(host.id(), new CreateRoomCommand("draw-guess", null, null));
  }

  protected StompTestClient connect(Guest guest) {
    StompTestClient client = StompTestClient.connect(port, guest.token());
    clients.add(client);
    return client;
  }

  /** Connects and subscribes to the room's three streams, then waits until they are live. */
  protected StompTestClient connectAndSubscribe(Guest guest, String code) {
    StompTestClient client = connect(guest);
    client.subscribeRoom(code);
    // A PING round trip proves the private subscription is registered before we rely on it.
    client.command(code, "{\"type\":\"PING\"}");
    client.awaitType("PONG");
    return client;
  }

  protected void closeClients() {
    clients.forEach(StompTestClient::close);
    clients.clear();
  }

  protected static String actionId() {
    return UUID.randomUUID().toString();
  }
}
