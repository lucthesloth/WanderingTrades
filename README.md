[![wandering trades logo](https://i.imgur.com/Ph27d08.png)](https://modrinth.com/plugin/wanderingtrades)

# WanderingTrades

![version](https://img.shields.io/badge/version-1.9.5--folia--SNAPSHOT-blue)
![minecraft](https://img.shields.io/badge/minecraft-1.21.11-green)
![folia](https://img.shields.io/badge/server-Folia%201.21.11-purple)
[![build](https://github.com/lucthesloth/WanderingTrades/actions/workflows/build.yml/badge.svg)](https://github.com/lucthesloth/WanderingTrades/actions/workflows/build.yml)

Fork of [jpenilla/WanderingTrades](https://github.com/jpenilla/WanderingTrades) maintained on the `folia-support` branch for Minecraft 1.21.11 and Folia 1.21.11.

## Branch Differences

This branch keeps the upstream WanderingTrades feature set and adds Folia-focused updates:

- Targets Minecraft/Paper API version `1.21.11`.
- Builds against `dev.folia:folia-api` from the PaperMC Maven repository.
- Marks the generated Paper plugin metadata as Folia-supported.
- Uses Folia-safe global, region, entity, and async schedulers instead of Bukkit main-thread scheduler calls.
- Uses VaultUnlocked as the optional permissions dependency instead of Vault.
- Configures `runServer` to launch a Folia 1.21.11 test server.
- Adds `ignored-perm`, defaulting to `wanderingtraders.ignoreplayer`, so ignored players are skipped for trader spawn origin relocation, trader spawn notifications, and player-head trader offers.

## Links

- [Original Project](https://github.com/jpenilla/WanderingTrades)
- [Original Modrinth Page](https://modrinth.com/plugin/wanderingtrades)
- [bStats](https://bstats.org/plugin/bukkit/WanderingTrades/7597)
- [Discord](https://discord.gg/g7CZdxt)

## Building

1. `git clone https://github.com/lucthesloth/WanderingTrades.git`
2. `cd WanderingTrades`
3. `git checkout folia-support`
4. `./gradlew build`

Built jars are written to `./build/libs/`.
