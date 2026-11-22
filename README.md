# fps display (Fabric, Minecraft 1.21.11)

Client-side mod. Press **V** while looking at a block:
rail -> TNT minecart -> fire (flint and steel) between you and the cart -> shoot loaded crossbow through the fire.

Needs in hotbar/inventory: any rail, TNT minecart, flint and steel, a LOADED crossbow.
If any is missing the mod disables itself and re-enables when you have them again.
Press V again mid-sequence to cancel. Rebind in Controls (category: Miscellaneous).

Build locally: install JDK 21 + Gradle 9.2, then `gradle build` -> `build/libs/fps display.jar`.
Build on GitHub: push this folder to a repo; Actions builds it and uploads the jar as an artifact.

Only use it where TNT/griefing is allowed - many servers ban it.
