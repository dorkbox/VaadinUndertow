/*
 * Copyright 2021 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

///////////////////////////////
//////    PUBLISH TO SONATYPE / MAVEN CENTRAL
////// TESTING : (to local maven repo) <'publish and release' - 'publishToMavenLocal'>
////// RELEASE : (to sonatype/maven central), <'publish and release' - 'publishToSonatypeAndRelease'>
///////////////////////////////

import dorkbox.gradle.kotlin
import java.time.Instant

gradle.startParameter.showStacktrace = ShowStacktrace.ALWAYS   // always show the stacktrace!

plugins {
    id("com.dorkbox.GradleUtils") version "2.11"
    id("com.dorkbox.Licensing") version "2.9"
    id("com.dorkbox.VersionUpdate") version "2.3"
    id("com.dorkbox.GradlePublish") version "1.11"

    kotlin("jvm") version "1.5.21"
}

object Extras {
    const val description = "Vaadin support for the Undertow web server"
    const val group = "com.dorkbox"
    const val name = "VaadinUndertow"
    const val id = "VaadinUndertow"
    const val version = "0.1"

    const val vendor = "Dorkbox LLC"
    const val vendorUrl = "https://dorkbox.com"
    const val url = "https://git.dorkbox.com/dorkbox/VaadinUndertow"

    val buildDate = Instant.now().toString()

    const val coroutineVer = "1.4.3"
    const val vaadinVer = "14.4.8" // this must be synchronized with the gradle plugin
    const val undertowVer = "2.2.10.Final"
}

///////////////////////////////
/////  assign 'Extras'
///////////////////////////////
GradleUtils.load("$projectDir/../../gradle.properties", Extras)
GradleUtils.defaults()
GradleUtils.compileConfiguration(JavaVersion.VERSION_1_8)
//GradleUtils.jpms(JavaVersion.VERSION_1_9)


licensing {
    license(License.APACHE_2) {
        description(Extras.description)
        author(Extras.vendor)
        url(Extras.url)

        extra("AhoCorasickDoubleArrayTrie", License.APACHE_2) {
            description(Extras.description)
            copyright(2018)
            author("hankcs <me@hankcs.com>")
            url("https://github.com/hankcs/AhoCorasickDoubleArrayTrie")
        }

        extra("Spring Boot", License.APACHE_2) {
            description("Undertow utility files")
            url("https://github.com/spring-projects/spring-boot/tree/main/spring-boot-project/spring-boot/src/main/java/org/springframework/boot/web/embedded/undertow")
        }

        extra("StubbornJava", License.MIT) {
            description("Unconventional Java code for building web servers / services without a framework")
            copyright(2017)
            author("Bill O'Neil")
            url("https://github.com/StubbornJava/StubbornJava")
        }
    }
}


sourceSets {
    main {
        java {
            setSrcDirs(listOf("src"))

            // want to include java+kotlin files for the source. 'setSrcDirs' resets includes...
            include("**/*.java", "**/*.kt")
        }
    }
}

kotlin {
    sourceSets {
        main {
            kotlin {
                setSrcDirs(listOf("src"))

                // want to include kotlin files for the source. 'setSrcDirs' resets includes...
                include("**/*.java", "**/*.kt")
            }
        }

        test {
            kotlin {
                setSrcDirs(listOf("test"))

                // want to include kotlin files for the source. 'setSrcDirs' resets includes...
                include("**/*.java", "**/*.kt")
            }
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Extras.coroutineVer}")

    compileOnly("com.vaadin:vaadin:${Extras.vaadinVer}")

    // we use undertow 2, with kotlin coroutines on top (with 1 actor per session)
    implementation("io.undertow:undertow-core:${Extras.undertowVer}")
    implementation("io.undertow:undertow-servlet:${Extras.undertowVer}")
    implementation("io.undertow:undertow-websockets-jsr:${Extras.undertowVer}")


    // Uber-fast, ultra-lightweight Java classpath and module path scanner
    implementation("io.github.classgraph:classgraph:4.8.114")

    implementation("com.dorkbox:Updates:1.1")

//    implementation("com.conversantmedia:disruptor:1.2.19")

    // awesome logging framework for kotlin.
    // https://www.reddit.com/r/Kotlin/comments/8gbiul/slf4j_loggers_in_3_ways/
    // https://github.com/MicroUtils/kotlin-logging
    implementation("io.github.microutils:kotlin-logging:2.0.11")

    implementation("org.slf4j:slf4j-api:1.7.32")
    implementation("org.slf4j:jul-to-slf4j:1.7.32")


    implementation("ch.qos.logback:logback-core:1.2.5")
    compileOnly("ch.qos.logback:logback-classic:1.2.5")


    testImplementation("junit:junit:4.13.2")
}

tasks.jar.get().apply {
    manifest {
        // https://docs.oracle.com/javase/tutorial/deployment/jar/packageman.html
        attributes["Name"] = Extras.name

        attributes["Specification-Title"] = Extras.name
        attributes["Specification-Version"] = Extras.version
        attributes["Specification-Vendor"] = Extras.vendor

        attributes["Implementation-Title"] = "${Extras.group}.${Extras.id}"
        attributes["Implementation-Version"] = Extras.buildDate
        attributes["Implementation-Vendor"] = Extras.vendor
    }
}

publishToSonatype {
    groupId = Extras.group
    artifactId = Extras.id
    version = Extras.version

    name = Extras.name
    description = Extras.description
    url = Extras.url

    vendor = Extras.vendor
    vendorUrl = Extras.vendorUrl

    issueManagement {
        url = "${Extras.url}/issues"
        nickname = "Gitea Issues"
    }

    developer {
        id = "dorkbox"
        name = Extras.vendor
        email = "email@dorkbox.com"
    }
}
