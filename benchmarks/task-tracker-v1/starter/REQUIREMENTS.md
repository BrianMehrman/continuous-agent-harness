# Task tracker requirements

Implement `example.tasktracker.TaskTracker`, runnable as:

```text
java -jar build/libs/task-tracker.jar --data-dir /data add "Buy milk"
java -jar build/libs/task-tracker.jar --data-dir /data list
java -jar build/libs/task-tracker.jar --data-dir /data complete 1
```

The starter provides a locked Gradle build, an empty class with `main`, and `REQUIREMENTS.md`. It contains no task-tracker implementation or evaluator assertions. The agent writes production Java, JUnit tests, and a usage README. No third-party production libraries are required; Java 21 standard APIs suffice. The locked build supplies a pinned JUnit test dependency and executable JAR manifest.

| Behavior | Exact contract |
|---|---|
| `add DESCRIPTION` | One nonblank argument; trim surrounding whitespace, preserve interior spaces and Unicode; reject tabs, CR, LF and NUL. Print a positive decimal ID and newline; exit 0 |
| ID allocation | First task is 1; increase by 1 for each successful add; never change an existing ID; duplicate descriptions are allowed |
| `list` | Print `ID<TAB>STATUS<TAB>DESCRIPTION` (`STATUS` is `OPEN` or `DONE`) plus newline for each task in ascending ID order; empty store prints nothing; exit 0 |
| `complete ID` | Positive decimal ID of an existing task; print `ID<TAB>DONE` and newline; exit 0; repeating completion is successful and leaves one task |
| Persistence | Separate processes using the same directory see prior tasks and status; different directories are independent; create a missing data directory |
| Invalid input | Unknown/missing command, wrong argument count, missing/invalid directory option, blank/control-character description, malformed/nonpositive/unknown ID: nonempty stderr, empty stdout, exit 2, no changes to existing task data |
| Storage failure | Unwritable directory or failed persistence: nonempty stderr, empty stdout, exit 1; never claim a successful update before persistence succeeds |
| Tests/docs | Include agent-authored behavioral tests and README examples for add/list/complete, persistence, and error exits |

Storage format is an implementation choice. No concurrent writers, editing/deleting tasks, timestamps, HTTP server, database dependency, authentication, or UI is required. The evaluator does not test undocumented behavior or inspect private implementation details. README completeness is recorded separately from executable correctness; initial automated checks require nonempty README and at least one discovered agent test, without pretending to judge documentation quality.


Build with `./gradlew jar` and run your tests with `./gradlew test`. Build output is `build/libs/task-tracker.jar`.

Only `src/main/java/**/*.java`, `src/test/java/**/*.java`, and `README.md` are writable. Build files, wrapper files, dependency locks, and these requirements are immutable. Limits: 64 files, 64 KiB UTF-8 per file, 1 MiB total. No delete or shell tool is available. Provide your implementation and tests; the trusted evaluator remains outside this workspace.

The binary Gradle wrapper JAR is a trusted runner asset. Its SHA-256 is readable in `gradle/wrapper/gradle-wrapper.jar.sha256`; it is not a UTF-8 source snapshot entry. The runner supplies the matching binary when staging builds.
