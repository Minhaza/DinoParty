package com.dinoparty.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.realtime.SubscriptionRegistry.Subscription;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubscriptionRegistryTest {

  private final SubscriptionRegistry registry = new SubscriptionRegistry();
  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();

  @Test
  void revokingAMemberOnlyAffectsThatMemberInThatRoom() {
    registry.authorize("s1", "sub1", new Subscription(alice, "ABC234", "/topic/rooms/ABC234"));
    registry.authorize("s2", "sub1", new Subscription(bob, "ABC234", "/topic/rooms/ABC234"));
    registry.authorize("s1", "sub2", new Subscription(alice, "XYZ789", "/topic/rooms/XYZ789"));

    registry.revokeMember(alice, "ABC234");

    assertThat(registry.isAuthorized("s1", "sub1")).isFalse();
    assertThat(registry.isAuthorized("s2", "sub1")).isTrue();
    assertThat(registry.isAuthorized("s1", "sub2")).isTrue();
  }

  @Test
  void revokingARoomDropsEverybodyButKeepsTheErrorsQueue() {
    registry.authorize("s1", "a", new Subscription(alice, "ABC234", "/topic/rooms/ABC234"));
    registry.authorize("s2", "a", new Subscription(bob, "ABC234", "/topic/rooms/ABC234"));
    registry.authorize("s1", "err", new Subscription(alice, null, "/user/queue/errors"));

    registry.revokeRoom("ABC234");

    assertThat(registry.isAuthorized("s1", "a")).isFalse();
    assertThat(registry.isAuthorized("s2", "a")).isFalse();
    assertThat(registry.isAuthorized("s1", "err")).isTrue();
  }

  @Test
  void countsAndSessionRemoval() {
    registry.authorize("s1", "a", new Subscription(alice, "ABC234", "/topic/rooms/ABC234"));
    registry.authorize("s1", "b", new Subscription(alice, "ABC234", "/user/queue/rooms/ABC234"));
    assertThat(registry.count("s1")).isEqualTo(2);

    registry.remove("s1", "a");
    assertThat(registry.count("s1")).isEqualTo(1);

    registry.removeSession("s1");
    assertThat(registry.count("s1")).isZero();
    assertThat(registry.sessions()).isZero();
  }

  @Test
  void unknownSubscriptionsAreNotAuthorized() {
    assertThat(registry.isAuthorized("nope", "nope")).isFalse();
  }
}
