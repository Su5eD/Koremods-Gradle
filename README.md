# Koremods Gradle

[![TeamCity build status](https://ci.su5ed.dev/app/rest/builds/buildType:id:KoremodsGradle_BuildBranch/statusIcon.svg)](https://ci.su5ed.dev/buildConfiguration/KoremodsGradle_BuildBranch)
[![Latest Release](https://maven.gofancy.wtf/api/badge/latest/releases/wtf/gofancy/koremods/koremods-gradle?color=40c14a&name=Latest%20Release)](https://maven.gofancy.wtf/releases/wtf/gofancy/koremods/koremods-gradle)
[![License](https://img.shields.io/gitlab/license/gofancy/koremods/koremods-gradle?color=brightgreen)](https://gitlab.com/gofancy/koremods/koremods-gradle/-/blob/master/LICENSE)

Koremods Gradle is a Gradle plugin created for the Koremods bytecode modification framework. Its goal is to pre-compile
Kotlin Scripts during build time to minimize runtime overhead.

## Usage

For installation and configuration instructions, visit the Koremods [usage guide](https://su5ed.dev/koremods).

## Motivation

Initially, all Koremods scripts were distributed in source form, and had to be compiled at runtime. This required
loading all compiler classes, processing script files and converting them to an executable format at once, which took
significant time. In addition, it required us to also distribute the heavy Kotlin embedded compiler, greatly increasing
our bundle size. On the technical side of things, Kotlin scripts are compiled to java bytecode on the fly, and then
loaded as classes into the JVM.

In an effort to improve script evaluation times, Koremods Gradle was created.

## How it works

Internally in the Kotlin Scripting library, the execution process is split into 2 parts - compilation and evaluation.
Thanks to the API for both parts being public, we can easily separate them between program build time and run time.  

Koremods Gradle hooks into Gradle resource processing, compiling script files and replacing them in the jar artifact
with the compilation output (usually a jar file). For faster re-compilation when making adjustments to scripts, it
leverages the [Gradle Worker API](https://docs.gradle.org/current/userguide/worker_api.html) to load the scripting
compiler in the background as a Daemon process. This way, all the necessary classes are always loaded when you need
them.

All of Koremods Gradle code is fully documented and commented, so feel free to explore and learn from it. We welcome any
improvements, too!
