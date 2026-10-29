# GTNH Queue ETA

**Status: experimental prototype.** Queue estimates are unreliable and machine
compatibility is still being tested. This project is not ready for general use.

GTNH Queue ETA is a Java mod prototype intended to add an ETA readout to
GregTech machine GUIs in GTNH.

The goal is simple: if a machine already has a bunch of work lined up, you should be able to glance at the GUI and get a feel for how long the whole batch still has left to run.

Right now the mod focuses on GregTech basic machine GUIs. When the machine state is clear enough, it shows the total remaining queued workload. If the full queue cannot be resolved cleanly, it falls back to the current recipe instead of pretending to know more than it does.

## Contents

- [Intended Behavior](#intended-behavior)
- [Current Scope](#current-scope)
- [Installation](#installation)
- [Config](#config)
- [Supported Machines](#supported-machines)
- [Reporting Bugs](#reporting-bugs)
- [Bug Report Template](#bug-report-template)

## Intended Behavior

- Adds a small `ETA: ...` line to the top-left of supported GregTech machine GUIs.
- Estimates total queued processing time from the machine's current workload.
- Falls back to current-recipe timing when full queue simulation is not reliable.
- Includes an optional debug overlay for testing machine compatibility and bug reports.

## Current Scope

The current prototype targets standard GregTech basic machine GUIs.

That means it is a good fit for the usual singleblock processing machines, like the common LV/MV/HV machine family. It is not meant to promise perfect support for every GregTech-adjacent UI yet.

Known limits right now:

- Multiblocks are not guaranteed.
- Unsupported or ambiguous machines may show no ETA at all.
- If GregTech does not expose enough reliable state for a machine, the mod will stay quiet rather than show a misleading number.

## Installation

1. Download the built jar from the latest release or build it yourself with `./gradlew.bat build`.
2. Put the jar into your GTNH instance `mods` folder.
3. Start the game and open a supported GregTech machine GUI.

If you are building from source, the output jar will be in `build/libs`.

## Config

After the mod has been run once, Forge will generate a config file in your instance `config` folder.

Useful settings:

- `enableTooltip=true`
- `enableDebugOverlay=false`
- `showDebugTicks=false`

The debug overlay is off by default. Turn it on only if you are testing support for a machine or trying to file a bug report with extra detail.

## Supported Machines

The mod is currently built around GregTech `MTEBasicMachine`-style GUIs.

That is the support target for `v0.1.x`. Compatibility has not been established across this machine family. Bug reports
with a screenshot and machine name help identify failures.

## Reporting Bugs

If you run into a machine that gives a wrong ETA, no ETA, or a weird flicker, please open an issue. A little context goes a long way and makes it much easier to fix quickly.

Please include:

- The exact machine name you tested.
- A screenshot of the GUI while the problem is happening.
- Whether `enableDebugOverlay` was on or off.
- Your GTNH version or pack version.
- What you expected to happen.
- What actually happened instead.

If you are reporting a machine compatibility problem, turning on `enableDebugOverlay=true` first is especially helpful.

## Bug Report Template

You can copy this straight into an issue:

```md
### Machine
Basic Bending Machine (LV)

### GTNH Version
2.x.x

### What Happened
The ETA only shows the current recipe instead of the whole queued workload.

### What I Expected
I expected the ETA to include all items currently queued in the machine.

### Debug Overlay
On / Off

### Screenshot
Attach a screenshot of the open machine GUI here.
```
