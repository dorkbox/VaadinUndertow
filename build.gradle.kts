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

import java.time.Instant

repositories {
    mavenCentral()
}
gradle.startParameter.showStacktrace = ShowStacktrace.ALWAYS   // always show the stacktrace!

plugins {
    id("com.dorkbox.GradleUtils") version "3.8"
    id("com.dorkbox.Licensing") version "2.19"
    id("com.dorkbox.VersionUpdate") version "2.5"
    id("com.dorkbox.GradlePublish") version "1.17"

    kotlin("jvm") version "1.7.22"
}

object Extras {
    const val description = "Vaadin support for the Undertow web server"
    const val group = "com.dorkbox"
    const val name = "VaadinUndertow"
    const val id = "VaadinUndertow"

    const val version = "14.9.1"

    const val vendor = "Dorkbox LLC"
    const val vendorUrl = "https://dorkbox.com"
    const val url = "https://git.dorkbox.com/dorkbox/VaadinUndertow"

    val buildDate = Instant.now().toString()

    // These MUST be in lock-step with what the GradleVaadin (other project) + gradle.build.kts + VaadinApplication.kt define, otherwise horrific errors can occur.
    const val vaadinVer = "14.9.4"
    const val undertowVer = "2.2.22.Final"
}

///////////////////////////////
/////  assign 'Extras'
///////////////////////////////
GradleUtils.load("$projectDir/../../gradle.properties", Extras)
GradleUtils.defaults()
GradleUtils.compileConfiguration(JavaVersion.VERSION_11)
//GradleUtils.jpms(JavaVersion.VERSION_1_9)  vaadin doesn't support jpms yet


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

        extra("Pronghorn HTTP Server", License.APACHE_2) {
            description("A low-level, high performance HTTP server")
            copyright(2017)
            author("Pronghorn Technology LLC")
            url("https://github.com/pronghorn-tech/server")
        }
    }
}

dependencies {
    api("org.jetbrains.kotlin:kotlin-reflect")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")

    compileOnly("com.vaadin:vaadin:${Extras.vaadinVer}")

    // we use undertow 2, with kotlin coroutines on top (with 1 actor per session)
    api("io.undertow:undertow-core:${Extras.undertowVer}")
    api("io.undertow:undertow-servlet:${Extras.undertowVer}")
    api("io.undertow:undertow-websockets-jsr:${Extras.undertowVer}")

    // Uber-fast, ultra-lightweight Java classpath and module path scanner
    api("io.github.classgraph:classgraph:4.8.154")

    api("com.dorkbox:Updates:1.1")

//    implementation("com.conversantmedia:disruptor:1.2.19")

    // awesome logging framework for kotlin.
    // https://www.reddit.com/r/Kotlin/comments/8gbiul/slf4j_loggers_in_3_ways/
    // https://github.com/MicroUtils/kotlin-logging
    api("io.github.microutils:kotlin-logging:3.0.4")

    // 1.8.0-beta4 supports jpms
    api("org.slf4j:slf4j-api:2.0.5")
    api("org.slf4j:jul-to-slf4j:2.0.5")


//    api("ch.qos.logback:logback-core:1.4.5")
//    compileOnly("ch.qos.logback:logback-classic:1.4.5")
//
//
//    testImplementation("com.vaadin:vaadin:${Extras.vaadinVer}")
//    testImplementation("ch.qos.logback:logback-classic:1.4.5")
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
