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
          .resideInAnyPackage("com.dinoparty.health..", "com.dinoparty.web..");

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
          .resideInAPackage("..persistence..")
          .allowEmptyShould(true); // no entities exist until Phase 2
}
