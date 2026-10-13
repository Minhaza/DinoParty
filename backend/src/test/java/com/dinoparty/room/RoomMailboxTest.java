package com.dinoparty.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RoomMailboxTest {

  private final RoomMailbox mailbox = new RoomMailbox();

  @Test
  void commandsForTheSameRoomNeverOverlap() throws Exception {
    AtomicInteger inside = new AtomicInteger();
    AtomicInteger maxInside = new AtomicInteger();
    int[] counter = {0}; // deliberately unsynchronized: only the mailbox protects it
    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  for (int i = 0; i < 500; i++) {
                    mailbox.run(
                        "ROOM22",
                        () -> {
                          maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                          counter[0]++;
                          inside.decrementAndGet();
                          return null;
                        });
                  }
                  return null;
                }));
      }
      go.countDown();
      for (Future<?> f : futures) {
        f.get();
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(maxInside.get()).isEqualTo(1);
    assertThat(counter[0]).isEqualTo(4000);
  }

  @Test
  void differentRoomsDoNotBlockEachOther() {
    String result = mailbox.run("AAAAAA", () -> mailbox.run("BBBBBB", () -> "nested"));

    assertThat(result).isEqualTo("nested");
  }

  @Test
  void idleSlotsAreReleasedEvenWhenTheCommandFails() {
    try {
      mailbox.run(
          "CCCCCC",
          () -> {
            throw new IllegalStateException("boom");
          });
    } catch (IllegalStateException expected) {
      // expected
    }

    assertThat(mailbox.activeSlots()).isZero();
  }
}
