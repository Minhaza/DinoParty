package com.dinoparty.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Module boundaries from docs/architecture.md §3.1. New modules extend this as they appear. */
@AnalyzeClasses(packages = "com.dinoparty", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule commonDependsOnNoOtherModule =
      noClasses()
          .that()
          .resideInAPackage("com.dinoparty.common..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.dinoparty.health..",
              "com.dinoparty.web..",
              "com.dinoparty.guest..",
              "com.dinoparty.room..",
              "com.dinoparty.realtime..",
              "com.dinoparty.engine..",
              "com.dinoparty.session..",
              "com.dinoparty.history..",
              "com.dinoparty.drawing..",
              "com.dinoparty.games..");

  @ArchTest
  static final ArchRule guestDependsOnlyOnCommon =
      noClasses()
          .that()
          .resideInAPackage("com.dinoparty.guest..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.dinoparty.room..",
              "com.dinoparty.web..",
              "com.dinoparty.health..",
              "com.dinoparty.realtime..");

  @ArchTest
  static final ArchRule roomDoesNotDependOnWebOrHealth =
      noClasses()
          .that()
          .resideInAPackage("com.dinoparty.room..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.dinoparty.web..", "com.dinoparty.health..", "com.dinoparty.realtime..");

  /** realtime sits on top of room and guest and must not reach into the REST layer. */
  @ArchTest
  static final ArchRule realtimeDoesNotDependOnWebOrHealth =
      noClasses()
          .that()
          .resideInAPackage("com.dinoparty.realtime..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.dinoparty.web..", "com.dinoparty.health..");

  @ArchTest
  static final ArchRule guestPersistenceIsPrivateToGuest =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.dinoparty.guest..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.dinoparty.guest.persistence..");

  @ArchTest
  static final ArchRule roomPersistenceIsPrivateToRoom =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.dinoparty.room..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.dinoparty.room.persistence..");

  @ArchTest
  static final ArchRule wordsPersistenceIsPrivateToWords =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.dinoparty.words..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.dinoparty.words.persistence..");

  /** The word catalog is reference data: it knows nothing of rooms, sessions or games. */
  @ArchTest
  static final ArchRule wordsDependOnlyOnCommon =
      noClasses()
          .that()
          .resideInAPackage("com.dinoparty.words..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.dinoparty.guest..",
              "com.dinoparty.room..",
              "com.dinoparty.engine..",
              "com.dinoparty.session..",
              "com.dinoparty.games..",
              "com.dinoparty.drawing..",
              "com.dinoparty.history..",
              "com.dinoparty.realtime..",
              "com.dinoparty.web..",
              "com.dinoparty.health..");

  @ArchTest
  static final ArchRule wordPacksPersistenceIsPrivateToWordPacks =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.dinoparty.wordpacks..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.dinoparty.wordpacks.persistence..");

  /** Word packs sit on rooms (authorization) and the word pool SPI, never on transport or games. */
  @ArchTest
  static final ArchRule wordPacksDoNotDependOnTransportSessionsOrGames =
      noClasses()
          .that()
          .resideInAPackage("com.dinoparty.wordpacks..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.dinoparty.web..",
              "com.dinoparty.realtime..",
              "com.dinoparty.session..",
              "com.dinoparty.engine..",
              "com.dinoparty.games..",
              "com.dinoparty.history..",
              "com.dinoparty.health..",
              "com.dinoparty.room.persistence..",
              "com.dinoparty.guest.persistence..");

  @ArchTest
  static final ArchRule wordCatalogKnowsNothingOfWordPacks =
      noClasses()
          .that()
          .resideInAPackage("com.dinoparty.words..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.dinoparty.wordpacks..");

  @ArchTest
  static final ArchRule realtimeDoesNotTouchPersistence =
      noClasses()
          .that()
          .resideInAPackage("com.dinoparty.realtime..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..persistence..", "jakarta.persistence..");

  @ArchTest
  static final ArchRule healthDoesNotDependOnWeb =
      noClasses()
          .that()
          .resideInAPackage("com.dinoparty.health..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.dinoparty.web..");

  @ArchTest
  static final ArchRule noFieldInjection =
      fields()
          .should()
          .notBeAnnotatedWith(org.springframework.beans.factory.annotation.Autowired.class);

  @ArchTest
  static final ArchRule jpaEntitiesLiveInPersistencePackages =
      classes()
          .that()
          .areAnnotatedWith(jakarta.persistence.Entity.class)
          .should()
          .resideInAPackage("..persistence..");

  /** The engine SPI is pure: games and the engine never reach into rooms, sockets or storage. */
  @ArchTest
  static final ArchRule engineDependsOnlyOnCommon =
      noClasses()
          .that()
          .resideInAPackage("com.dinoparty.engine..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.dinoparty.room..",
              "com.dinoparty.session..",
              "com.dinoparty.history..",
              "com.dinoparty.realtime..",
              "com.dinoparty.web..",
              "com.dinoparty.guest..",
              "com.dinoparty.health..",
              "..persistence..",
              "jakarta.persistence..",
              "org.springframework.web..",
              "org.springframework.messaging..");

  @ArchTest
  static final ArchRule gamesDependOnlyOnTheEngine =
      noClasses()
          .that()
          .resideInAPackage("com.dinoparty.games..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.dinoparty.room..",
              "com.dinoparty.session..",
              "com.dinoparty.history..",
              "com.dinoparty.realtime..",
              "com.dinoparty.web..",
              "com.dinoparty.guest..",
              "..persistence..",
              "jakarta.persistence..")
          .allowEmptyShould(true);

  /** The shared stroke library is pure: only common, so both drawing games can use it. */
  @ArchTest
  static final ArchRule drawingDependsOnlyOnCommon =
      noClasses()
          .that()
          .resideInAPackage("com.dinoparty.drawing..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.dinoparty.engine..",
              "com.dinoparty.games..",
              "com.dinoparty.room..",
              "com.dinoparty.session..",
              "com.dinoparty.history..",
              "com.dinoparty.realtime..",
              "com.dinoparty.web..",
              "com.dinoparty.guest..",
              "com.dinoparty.health..",
              "org.springframework..",
              "..persistence..",
              "jakarta.persistence..");

  /**
   * Draw & Guess uses the engine SPI, the drawing library, the word pool and common only (design
   * §10; the word catalog's persistence stays private to {@code words}).
   */
  @ArchTest
  static final ArchRule drawGuessUsesEngineDrawingAndCommonOnly =
      classes()
          .that()
          .resideInAPackage("com.dinoparty.games.drawguess..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage(
              "com.dinoparty.games.drawguess..",
              "com.dinoparty.engine..",
              "com.dinoparty.drawing..",
              "com.dinoparty.words",
              "com.dinoparty.common..",
              "org.springframework.stereotype..",
              "java..");

  /** Telephone uses the engine SPI, the drawing library, the word pool and common only. */
  @ArchTest
  static final ArchRule telephoneUsesEngineDrawingAndCommonOnly =
      classes()
          .that()
          .resideInAPackage("com.dinoparty.games.telephone..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage(
              "com.dinoparty.games.telephone..",
              "com.dinoparty.engine..",
              "com.dinoparty.drawing..",
              "com.dinoparty.words",
              "com.dinoparty.common..",
              "org.springframework.stereotype..",
              "java..");

  /** The session module talks to sockets and storage only through its ports. */
  @ArchTest
  static final ArchRule sessionUsesPortsOnly =
      noClasses()
          .that()
          .resideInAPackage("com.dinoparty.session..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.dinoparty.realtime..",
              "com.dinoparty.web..",
              "com.dinoparty.history..",
              "..persistence..",
              "jakarta.persistence..");

  @ArchTest
  static final ArchRule historyDoesNotDependOnTransportOrRooms =
      noClasses()
          .that()
          .resideInAPackage("com.dinoparty.history..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.dinoparty.realtime..",
              "com.dinoparty.web..",
              "com.dinoparty.room..",
              "com.dinoparty.guest..");

  @ArchTest
  static final ArchRule roomKnowsNothingOfTheEngine =
      noClasses()
          .that()
          .resideInAPackage("com.dinoparty.room..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.dinoparty.engine..", "com.dinoparty.session..", "com.dinoparty.history..");

  /** Game state is computed from the injected clock and random source only. */
  @ArchTest
  static final ArchRule noAmbientTimeOrRandomnessInTheEngine =
      noClasses()
          .that()
          .resideInAnyPackage(
              "com.dinoparty.engine..",
              "com.dinoparty.session..",
              "com.dinoparty.games..",
              "com.dinoparty.drawing..")
          .should()
          .callMethod(java.time.Instant.class, "now")
          .orShould()
          .callConstructor(java.util.Random.class)
          .orShould()
          .callMethod(Math.class, "random")
          .allowEmptyShould(true);

  /** All room-state mutation goes through the room mailbox; no ad hoc locks in game code. */
  @ArchTest
  static final ArchRule noThreadsInEngineOrGames =
      noClasses()
          .that()
          .resideInAnyPackage(
              "com.dinoparty.engine..", "com.dinoparty.games..", "com.dinoparty.drawing..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("java.lang.Thread")
          .allowEmptyShould(true);
}
