# Security Policy

## Reporting a Vulnerability

Please do not report security vulnerabilities through public GitHub issues.

Use GitHub Private Vulnerability Reporting through
[Security Advisories](https://github.com/nikosavola/AnkiDroid-WearOS/security/advisories), or
contact @nikosavola directly.

### Scope

This project runs on your own phone and watch, and talks to AnkiDroid through its public
ContentProvider. Reports that matter here include:

- Another app on either device reading, changing or deleting flashcard data through this app.
- A crafted Data Layer message corrupting the watch's local buffer or injecting grades into
  AnkiDroid.
- Card content leaking into logs, backups or rendered images.
- Data leaving the paired devices, or the offline promise being broken in any way.

Problems in AnkiDroid itself, including its ContentProvider, belong to the
[AnkiDroid project](https://github.com/ankidroid/Anki-Android/security/policy).

### What to include in a report

- What the vulnerability is and what an attacker can do with it.
- Steps to reproduce it, ideally with a minimal example.
- Any mitigation you have already identified.
- The app versions, phone and watch models, and AnkiDroid version involved.

### Process

1. I acknowledge the report.
2. I reproduce and confirm the issue.
3. I develop and test a fix.
4. I release the fix and credit the reporter in the release notes, unless you prefer to stay
   anonymous.
