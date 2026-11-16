plugins {
    java
}

group = "ru.mgp"
version = "0.11.3"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper API под Minecraft 26.3 (последняя сборка). Для другой версии замени "26.3".
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25) // Paper 26.x требует Java 25
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
