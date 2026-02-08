# CLAUDE.md

This file provides guidance for AI assistants working with the sonar-gosu codebase.

## Project Overview

**Sonar Gosu** is a SonarQube plugin that enables static analysis of Gosu source code. It integrates CodeNarc for rule-based issue detection, GMetrics for cyclomatic complexity metrics, and supports Cobertura/JaCoCo for code coverage reporting. The plugin targets SonarQube >= 5.6.

**Status:** Deprecated. Guidewire recommends using the SonarQube plug-in Accelerator from their Marketplace instead.

**License:** GNU LGPL 3

## Repository Structure

```
sonar-gosu/
├── pom.xml                         # Parent Maven POM (modules, properties)
├── sonar-gosu-plugin/              # Main plugin module
│   ├── pom.xml                     # Plugin POM (dependencies, enforcer)
│   └── src/
│       ├── main/java/org/sonar/plugins/gosu/
│       │   ├── GosuPlugin.java             # Plugin entry point (registers 16 extensions)
│       │   ├── GosuSensor.java             # Main analysis sensor (metrics via Groovy lexer)
│       │   ├── GosuMetrics.java            # Custom coupling metrics
│       │   ├── foundation/                 # Language definition, file system, tokenizer
│       │   ├── codenarc/                   # CodeNarc integration (static analysis rules)
│       │   ├── jacoco/                     # JaCoCo code coverage (UT, IT, overall)
│       │   ├── cobertura/                  # Cobertura code coverage
│       │   ├── surefire/                   # JUnit test result import
│       │   ├── gmetrics/                   # GMetrics complexity analysis
│       │   └── utils/                      # StAX XML parser utility
│       ├── main/resources/.../gosu/
│       │   ├── rules.xml                   # CodeNarc rule definitions (14+ rules)
│       │   ├── profile-sonar-way.xml       # Default quality profile
│       │   └── cost.csv                    # Rule remediation costs
│       └── test/                           # Mirror structure with 25 test classes
├── gosu-jacoco-previous/           # Shaded JaCoCo 0.7.4 for binary format compatibility
│   └── pom.xml
└── tools/
    └── sonar-codenarc-converter/   # Utility to convert CodeNarc rules
```

## Build System

**Build tool:** Apache Maven

### Common Commands

```bash
# Full build with tests
mvn clean verify

# Build without tests (matches CI)
mvn verify -B -e -V -DskipTests=true

# Run tests only
mvn test

# Build specific module
mvn -pl sonar-gosu-plugin clean verify

# Package plugin JAR
mvn clean package
```

### Maven Modules

| Module | Purpose |
|--------|---------|
| `sonar-gosu-plugin` | Main SonarQube plugin (packaging: `sonar-plugin`) |
| `gosu-jacoco-previous` | Shaded JaCoCo 0.7.4 with relocated classes for backward compatibility |

### Key Maven Properties (parent POM)

| Property | Value |
|----------|-------|
| `sonar.version` | 5.6 |
| `jacoco.version` | 0.7.5.201505241946 |
| `jacoco.previous.version` | 0.7.4.201502262128 |
| `groovy.version` | 2.4.4 |

### Build Validation

The Maven Enforcer plugin validates the final plugin JAR is between 8.5 MB and 15 MB during the `verify` phase.

## Architecture

### Plugin Entry Point

`GosuPlugin.java` registers 16 extensions with SonarQube via `context.addExtensions()`:
- Language definition (`Gosu`)
- File system utilities (`GosuFileSystem`)
- Sensors: `GosuSensor`, `CodeNarcSensor`, `CoberturaSensor`, `GosuSurefireSensor`
- Coverage sensors: `JaCoCoSensor`, `JaCoCoItSensor`, `JaCoCoOverallSensor`
- Rules and profiles: `CodeNarcRulesDefinition`, `SonarWayProfile`
- Configuration: `JaCoCoConfiguration`
- Metrics: `GosuMetrics`

### Key Packages

| Package | Responsibility |
|---------|---------------|
| `foundation` | Gosu language definition (key: `"gosu"`, suffixes: `.gs`, `.gsx`), file filtering, syntax highlighting |
| `codenarc` | Parses CodeNarc XML reports, defines rules from `rules.xml`, creates SonarQube issues |
| `jacoco` | Reads JaCoCo `.exec` binary files, merges multi-version reports, publishes line coverage |
| `cobertura` | Parses Cobertura XML coverage reports |
| `surefire` | Imports JUnit/Surefire XML test execution results |
| `gmetrics` | Computes cyclomatic complexity and coupling metrics via GMetrics |

### Design Patterns

- **Sensor pattern:** All analysis components implement `org.sonar.api.batch.sensor.Sensor`
- **Constructor-based DI:** SonarQube injects dependencies via constructors
- **StAX XML parsing:** Non-blocking XML parsing via `StaxParser` utility
- **Visitor pattern:** `ExecutionDataVisitor` for JaCoCo coverage data traversal

## Testing

### Framework and Libraries

| Library | Version | Purpose |
|---------|---------|---------|
| JUnit | 4.10 | Test framework |
| FEST Assertions | 1.4 | Fluent assertion API (`assertThat(x).isEqualTo(y)`) |
| Mockito | (via sonar-testing-harness) | Mocking framework |
| XMLUnit | 1.6 | XML comparison/validation |
| sonar-testing-harness | 5.6 | SonarQube-specific test utilities |

### Running Tests

```bash
# Run all tests
mvn test

# Run a specific test class
mvn -pl sonar-gosu-plugin -Dtest=GosuSensorTest test

# Run tests with verbose output
mvn test -e
```

### Test Conventions

- Test classes are named `*Test.java` and mirror the source structure
- Test methods use `shouldXxx()` or `testXxx()` naming
- Assertions use FEST fluent style: `assertThat(result).isEqualTo(expected)`
- Dependencies are mocked via constructor injection with Mockito
- Test resources include JaCoCo `.exec` files, Surefire XML reports, and sample Gosu source files

## Code Conventions

### Naming

- **Packages:** `org.sonar.plugins.gosu.*` hierarchical structure
- **Classes:** PascalCase (`GosuSensor`, `CodeNarcRulesDefinition`)
- **Constants:** UPPER_SNAKE_CASE (`CYCLOMATIC_COMPLEXITY_METRIC_NAME`)
- **Methods:** camelCase starting with verb (`describe()`, `execute()`, `analyze()`)
- **Variables:** camelCase (`fileLinesContext`, `gosuFileSystem`)

### Style

- No wildcard imports; explicit imports only
- One public class per file
- Package-private visibility where possible; public only for SonarQube extension points
- `@CheckForNull` and `@Nullable` annotations for null safety
- `@ParametersAreNonnullByDefault` at package level
- Guava immutable collections (`ImmutableList`, `ImmutableMap`)
- GNU LGPL 3 license header in all Java source files
- Minimal inline comments; code is kept self-documenting

### Configuration Properties

Plugin properties are prefixed with `sonar.gosu.*`:

| Property | Default | Description |
|----------|---------|-------------|
| `sonar.gosu.file.suffixes` | `.gs,.gsx` | Gosu file extensions to analyze |
| `sonar.gosu.codenarc.reportPath` | (none) | Path to pre-generated CodeNarc XML report |
| `sonar.gosu.cobertura.reportPath` | (none) | Path to Cobertura XML coverage report |
| `sonar.gosu.jacoco.reportPath` | (none) | Path to JaCoCo UT `.exec` file |
| `sonar.gosu.jacoco.itReportPath` | (none) | Path to JaCoCo IT `.exec` file |
| `sonar.gosu.binaries` | (none) | Comma-separated paths to compiled class directories |

## CI/CD

**CI provider:** Travis CI (`.travis.yml`)
- Java 8 (Oracle JDK)
- Build command: `mvn verify -B -e -V -DskipTests=true`
- Maven repository cached at `$HOME/.m2/repository`
- Email notifications disabled

## Dependencies Summary

### Runtime

| Dependency | Version | Purpose |
|-----------|---------|---------|
| sonar-plugin-api | 5.6 | SonarQube plugin framework (provided) |
| CodeNarc | 0.25.2 | Static analysis rule engine |
| GMetrics | 0.7 | Complexity metrics (transitive via CodeNarc) |
| JaCoCo Core | 0.7.5 | Code coverage analysis |
| Guava | 10.0.1 | Collection utilities |
| Commons IO | 2.4 | File I/O helpers |
| Commons Lang | 2.6 | String/utility functions |
| Apache Ant | 1.9.7 | FileSet support for GMetrics |
| Groovy | 2.4.4 | Lexer/tokenizer (provided) |
| Log4J | 1.2.17 | Logging (provided) |

### Test Only

| Dependency | Version | Purpose |
|-----------|---------|---------|
| sonar-testing-harness | 5.6 | SonarQube test utilities |
| JUnit | 4.10 | Test framework |
| FEST Assertions | 1.4 | Fluent assertions |
| XMLUnit | 1.6 | XML testing |
| Logback Classic | 0.9.30 | Test logging |
| Maven Project | 2.0.7 | Maven mock support |

## Error Handling

- User-facing errors use SonarQube's `MessageException`
- Internal errors are logged via SonarQube's `Loggers.get()` facade
- Missing reports are logged as warnings, not fatal errors (graceful degradation)

## Key Files for Common Tasks

| Task | Files to modify |
|------|----------------|
| Add a new analysis rule | `rules.xml`, `profile-sonar-way.xml`, `cost.csv`, `CodeNarcRulesDefinition.java` |
| Add a new sensor | New `*Sensor.java`, register in `GosuPlugin.java` |
| Add a new metric | `GosuMetrics.java`, update relevant sensor |
| Change supported file extensions | `Gosu.java` (default suffixes) |
| Modify coverage integration | `jacoco/` or `cobertura/` package |
| Update test result parsing | `surefire/` package |
