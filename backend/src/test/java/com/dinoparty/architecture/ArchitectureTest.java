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
              "com.dinoparty.realtime..");

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
}
