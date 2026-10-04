plugins {
    id("dev.kikugie.stonecutter")
}

// The active version decides the state of the `//? if` comments in the shared src/ tree and must
// be assigned exactly once. 1.21.1 is the committed ("vcs") state, so a checked-out working copy
// always equals the 1.21.1 sources; switching to another version rewrites this line in place and
// must not be committed.
stonecutter active "1.21.1"

// Builds every registered version, e.g. `./gradlew buildAll`.
// Until a version is ported its build is expected to fail - see docs/VERSIONING.md.
tasks.register("buildAll") {
    group = "build"
    description = "Builds every registered Minecraft version."
    dependsOn(stonecutter.tasks.named("build"))
}

// Runs the headless checks for every registered version.
tasks.register("verifyAll") {
    group = "verification"
    description = "Runs verifyEphemeris and checkLangKeys on every registered version."
    dependsOn(stonecutter.tasks.named("verifyEphemeris"))
    dependsOn(stonecutter.tasks.named("checkLangKeys"))
}
