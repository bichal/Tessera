# Requirements and files

| Your setup | Tessera |
|---|---|
| **Minecraft** 1.21 to 1.21.11 and 26.1--26.3, NeoForge |  Download the file made for your version |
| **Windows** or **Linux** |  Supported |
| **macOS** |  macOS doesn't support the texture format Tessera needs |
| **Servers** |  Not needed: it runs on your computer only, and you can join any server with it |

| Minecraft | Tessera file |
|---|---|
| 1.21 to 1.21.4 | `tessera-<version>+1.21.1.jar` |
| 1.21.5--1.21.10 | `tessera-<version>+1.21.10.jar` |
| 1.21.11 | `tessera-<version>+1.21.11.jar` |
| 26.1--26.3 | `tessera-<version>+26.1.jar` |

Tessera works without any setup. These JVM flags unlock Celeris's fastest code paths:

| Minecraft (Java) | Add to your JVM arguments |
|---|---|
| 1.21.x (Java 21) | `--enable-preview --add-modules=jdk.incubator.vector` |
| 26.x and up (Java 25+) | `--add-modules=jdk.incubator.vector` |

Leave `--enable-native-access` out: on Java 21 it switches Celeris's native code off.

Where to put them:

- **Modrinth App**: instance > Settings > Java and memory > Java arguments
- **CurseForge App**: Settings > Minecraft > Additional arguments
- **Prism Launcher**: instance > Settings > Java > JVM arguments

Without the flags nothing breaks; each feature uses its pure-Java fallback. Use `--enable-preview` only with the Java 21
that Minecraft 1.21.x ships with.

[Back to Home](Home.md)
